package storrent.pwp

import akka.actor._
import akka.pattern._
import akka.util.Timeout
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
  val id = PeerId()

  val announceTimeout = Timeout(5.minutes)
  val announcer = context.actorOf(Announcer.props(id, port, torrent, self))

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
    context.system.scheduler.scheduleOnce(0.seconds, self, DoAnnounce)
  }

  override def wrappedReceive: Receive = {
    case DoAnnounce =>
      //TODO 从stats中获取当前的下载进度
      announce(0, 0, torrent.metainfo.info.length.toInt, "") onComplete {
        case Success(peers) =>
          logger.info("Got peers: {}", peers)
          //TODO send peers to TorrentSession, let him judge
          peers.foreach { p =>
            peerConns.getOrElseUpdate(p, {
              peerListener ! PeerAdded(p)
              createPeer(p)
            })
          }
        case Failure(e) =>
          logger.error("Announce failure", e)
      }

    case (p: Peer, msg: Message) =>
      //转发消息
      peerConns.get(p) match {
        case Some(conn) =>
          conn.forward(msg)
        case None =>
          logger.warn("Unable to route message[{}] to peer[{}]", Array(msg, p))
      }
    // handle pwp message
    // TorrentHandler ? PieceHandler ?

    case Terminated(c) =>
      logger.info("Child Terminated {}", c)
      peerConns.retain((peer, conn) => {
        if (conn == c) {
          peerListener ! PeerRemoved(peer)
          false
        } else {
          true
        }
      })

  }

  def createPeer(p: Peer): ActorRef = {
    val c = context.actorOf(PeerConnection.props(torrent.infoHash, id, p, session))
    context.watch(c)
    c
  }
}
