package storrent.pwp

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import storrent.client.Announcer.Announce
import storrent.client.{ Announcer, TrackerResponse }
import storrent.{ Peer, PeerId, Torrent }

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object PwpPeer {

  private object DoAnnounce

}

class PwpPeer(torrent: Torrent,
              port: Int) extends Actor with ActorLogging {

  import context.dispatcher
  import storrent.pwp.PwpPeer._

  val peerConns = new mutable.HashMap[String, ActorRef]()
  val id = PeerId()

  implicit val announceTimeout = Timeout(5.minutes)
  val announcer = context.actorOf(Announcer.props(id, port, torrent, self))

  //TODO @stats(downloaded, uploaded) 如果这个采集器放在这里，那么就不够抽象了
  // 创建一个 TorrentStats 接口，在 TorrentHandler/PieceHandler的相关方法中传入，暴露修改接口

  def announce(uploaded: Int, downloaded: Int, left: Int, event: String = ""): Future[List[Peer]] = {
    val resp = (announcer ? Announce(uploaded, downloaded, left, event))(announceTimeout).mapTo[TrackerResponse]
    resp.onSuccess {
      case resp =>
        context.system.scheduler.scheduleOnce(resp.interval.seconds, self, DoAnnounce)
    }

    resp.map(_.peers)
  }

  override def preStart(): Unit = {
    //TODO start peer tcp listening
    context.system.scheduler.scheduleOnce(0.seconds, self, DoAnnounce)
  }

  override def receive: Receive = {
    case DoAnnounce =>
      //TODO 从stats中获取当前的下载进度
      announce(0, 0, 0, "") onSuccess {
        case peers => peers.foreach { p =>
          peerConns.getOrElseUpdate(p.id, createPeer(p))
        }
      }

    case (p: Peer, msg: Message) =>
    // handle pwp message
    // TorrentHandler ? PieceHandler ?

    case Terminated(c) =>
      peerConns.retain((_, p) => p != c)

  }

  def createPeer(p: Peer): ActorRef = {
    val c = context.actorOf(PeerConnection.props(torrent.info.hash, id, p), s"${torrent.info.hash}/${p.id}-conn")
    context.watch(c)
    c
  }
}
