package storrent.pwp

import java.net.InetSocketAddress

import akka.actor._
import akka.io.Tcp._
import akka.pattern._
import akka.util.Timeout
import akka.io._
import storrent.client.Announcer.Announce
import storrent.client.{ Announcer, TrackerResponse }
import storrent.pwp.PeerListener.{ PeerRemoved, PeerAdded }
import storrent._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object PwpPeer {

  private object DoAnnounce

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

  IO(Tcp)(context.system) ! Bind(self, new InetSocketAddress("localhost", port))

  //TODO @stats(downloaded, uploaded) 如果这个采集器放在这里，那么就不够抽象了
  // 创建一个 TorrentStats 接口，在 TorrentHandler/PieceHandler的相关方法中传入，暴露修改接口

  def announce(uploaded: Int, downloaded: Int, left: Int, event: String = ""): Future[List[Peer]] = {
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
  }

  def ready: Receive = {
    case DoAnnounce =>
      //TODO 从stats中获取当前的下载进度
      announce(0, torrent.metainfo.info.length.toInt, 0, "") onComplete {
        case Success(peers) =>
          logger.info("Got peers: {}", peers)
          //TODO send peers to TorrentSession, let him judge
          peers.foreach { p =>
            peerConns.getOrElseUpdate(p, {
              peerListener ! PeerAdded(p)
              createPeer(p, inbound = false)
            })
          }
        case Failure(e) =>
          logger.error("Announce failure", e)
      }

    case c @ Connected(remote, _) =>
      val conn = sender()
      val peer = Peer("", remote.getAddress.getHostAddress, remote.getPort)
      if (!peerConns.contains(peer)) {
        peerConns(peer) = createPeer(peer, inbound = true)
        peerConns(peer) forward c
        peerListener ! PeerAdded(peer)
      } else {
        conn ! Close
      }

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

    case Terminated(c) =>
      //      logger.info("Child Terminated {}", c)
      peerConns.retain((peer, conn) => {
        if (conn == c) {
          peerListener ! PeerRemoved(peer)
          false
        } else {
          true
        }
      })
  }

  def createPeer(p: Peer, inbound: Boolean): ActorRef = {
    val c = context.actorOf(PeerConnection.props(torrent.infoHash, id, p, session, inbound), s"PeerConn-${p.hashCode()}")
    context.watch(c)
    c
  }
}
