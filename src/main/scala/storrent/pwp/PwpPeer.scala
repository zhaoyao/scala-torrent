package storrent.pwp

import java.net.InetSocketAddress

import akka.actor._
import akka.io.Tcp._
import akka.pattern._
import akka.util.Timeout
import akka.io._
import storrent.client.Announcer.Announce
import storrent.client.{ Announcer, TrackerResponse }
import storrent.pwp.PeerConnection.Start
import storrent.pwp.PeerListener.{ PeerRemoved, PeerAdded }
import storrent._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object PwpPeer {

  private case class UpdatePeerStats(uploaded: Long, downloaded: Long, left: Long)
  private case object DoAnnounce
  private case class PeerUp(peer: Peer)
  private case class PeerDown(peer: Peer)

  case class AddPeer(peer: Peer, tcpConn: Option[ActorRef])

  def props(torrent: Torrent, port: Int, peerListener: ActorRef) = Props(classOf[PwpPeer], torrent, port, peerListener)

}

class PwpPeer(torrent: Torrent,
              port: Int,
              peerListener: ActorRef) extends ActorStack with Slf4jLogging {

  import context.dispatcher
  import storrent.pwp.PwpPeer._

  val session = context.parent
  val peerConns = new mutable.HashMap[Peer, ActorRef]()
  val connMapping = new mutable.HashMap[ActorRef, ActorRef]() /* Inbound PeerConnection => TcpConnection  */
  val id = PeerId()

  val announceTimeout = Timeout(5.minutes)
  val announcer = context.actorOf(Announcer.props(id, port, torrent, self))

  var downloaded: Long = 0
  var uploaded: Long = 0
  var left: Long = torrent.metainfo.info.length

  IO(Tcp)(context.system) ! Bind(self, new InetSocketAddress("localhost", port))

  //TODO @stats(downloaded, uploaded) 如果这个采集器放在这里，那么就不够抽象了
  // 创建一个 TorrentStats 接口，在 TorrentHandler/PieceHandler的相关方法中传入，暴露修改接口

  def announce(uploaded: Long, downloaded: Long, left: Long, event: String = ""): Future[List[Peer]] = {
    val resp = (announcer ? Announce(uploaded, downloaded, left, event))(announceTimeout).mapTo[TrackerResponse]
    resp.onSuccess {
      case TrackerResponse.Success(interval, _, _, _) =>
        context.system.scheduler.scheduleOnce(interval.seconds, self, DoAnnounce)

      case TrackerResponse.Error(msg) =>

      //TODO handle announce error
    }

    resp.flatMap {
      case TrackerResponse.Success(_, peers, _, _) => Future.successful(peers)
      case TrackerResponse.Error(msg)              => Future.failed(new RuntimeException("announce failed: " + msg))
    }
  }

  override def preStart(): Unit = {
    //TODO start peer tcp listening
    //    context.system.scheduler.schedule(10.seconds, 10.seconds, self, DumpPeers)
  }

  override def wrappedReceive: Receive = creatingTcpServer

  def creatingTcpServer: Receive = {
    case b @ Bound(_) =>
      logger.info(s"${torrent.infoHash} listen on $port success")
      context become ready
      context.system.scheduler.scheduleOnce(0.seconds, self, DoAnnounce)

    case CommandFailed(_: Bind) =>
      logger.warn(s"${torrent.infoHash} Failed to listen on $port")
      context stop self
  }

  def ready: Receive = {
    // suppress warning: a type was inferred to be `Any`; this may indicate a programming error.
    val receive: PartialFunction[Any, Unit] = inboundConnection orElse
      peerTerminated orElse
      peerStateChange orElse
      announce orElse
      peerMessage orElse
      peerRegistration
    receive
  }

  def inboundConnection: Receive = {
    case c @ Connected(remote, _) =>
      val conn = sender()
      val peer = Peer("", remote.getAddress.getHostAddress, remote.getPort)
      self ! AddPeer(peer, Some(conn))
  }

  def announce: Receive = {
    case UpdatePeerStats(u, d, l) =>
      uploaded = u
      downloaded = d
      left = l

    case DoAnnounce =>
      announce(uploaded, downloaded, left, "") onComplete {
        case Success(peers) =>
          logger.info(s"Got ${peers.size} peer(s) from tracker")
          //TODO send peers to TorrentSession, let him judge
          peers.foreach { p =>
            self ! AddPeer(p, None)
          }
        case Failure(e) =>
          logger.error("Announce failure", e)
      }
  }

  def peerRegistration: Receive = {
    case AddPeer(p, tcpConn) =>
      tcpConn match {
        case Some(c) =>
          if (peerConns.contains(p)) {
            logger.debug(s"Stopping old peer conn from $p")
            context stop peerConns(p)
          }
          peerConns(p) = createPeer(p, tcpConn)
        case None =>
          peerConns(p) = createPeer(p, tcpConn)
      }
  }

  def peerTerminated: Receive = {
    case Terminated(c) =>
      //      logger.info("Child Terminated {}", c)
      peerConns.retain((peer, conn) => {
        if (conn == c) {
          self ! PeerDown(peer)
          false
        } else {
          true
        }
      })
  }

  def peerStateChange: Receive = {
    case PeerUp(p) =>
      peerListener ! PeerAdded(p)

    case PeerDown(p) =>
      peerListener ! PeerRemoved(p)
  }

  def peerMessage: Receive = {
    case (p: Peer, msg: Message) =>
      //转发消息
      peerConns.get(p) match {
        case Some(conn) =>
          conn.forward(msg)
        case None =>
          logger.warn("Unable to route message[{}] to peer[{}]", msg, p)
      }
    // handle pwp message
    // TorrentHandler ? PieceHandler ?
    case (p: Peer, Kill) =>
      logger.info("Close peer connection: {}", p)
      peerConns.get(p) match {
        case Some(conn) =>
          conn ! Kill
        case None =>
          logger.warn("Unable to kill peer connection: {}", p)
      }
  }

  def createPeer(p: Peer, tcpConn: Option[ActorRef]) = {
    val c = context.actorOf(PeerConnection.props(torrent.infoHash, id, p, session, tcpConn.isDefined), s"PeerConn-${p.hashCode()}")
    context.watch(c)

    (c ? Start(tcpConn))(Timeout(PeerConnection.ConnectTimeout)).mapTo[Boolean].map({
      case true  => self ! PeerUp(p)
      case false => self ! PeerDown(p)
    })

    c
  }
}
