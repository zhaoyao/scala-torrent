package storrent.pwp

import java.nio.file.{Files, Paths}

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import storrent.client.Announcer.Announce
import storrent.client.{Announcer, TrackerResponse}
import storrent.{Peer, PeerId, Torrent}

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object PwpPeer {

  private object DoAnnounce

  def props(torrent: Torrent, port: Int) = Props(classOf[PwpPeer], torrent, port)

}

class PwpPeer(torrent: Torrent,
              port: Int) extends Actor with ActorLogging {

  import context.dispatcher
  import storrent.pwp.PwpPeer._

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
      case TrackerResponse.Error(msg) => Future.failed(new RuntimeException("announce failed: " + msg))
    }
  }

  override def preStart(): Unit = {
    //TODO start peer tcp listening
    context.system.scheduler.scheduleOnce(0.seconds, self, DoAnnounce)
  }

  override def receive: Receive = {
    case DoAnnounce =>
      //TODO 从stats中获取当前的下载进度
      announce(0, 0, torrent.metainfo.info.length.get.toInt, "") onComplete {
        case Success(peers) =>
          log.info("Got peers: {}", peers)
          peers.foreach { p =>
            peerConns.getOrElseUpdate(p, createPeer(p))
          }
        case Failure(e) =>
          log.error(e, "Announce failure")
      }

    case (p: Peer, msg: Message) =>
    // handle pwp message
    // TorrentHandler ? PieceHandler ?

    case Terminated(c) =>
      peerConns.retain((_, p) => p != c)

  }

  def createPeer(p: Peer): ActorRef = {
    val c = context.actorOf(PeerConnection.props(torrent.infoHash, id, p))
    context.watch(c)
    c
  }
}
