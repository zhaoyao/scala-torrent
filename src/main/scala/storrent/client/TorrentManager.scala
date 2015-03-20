package storrent.client

import akka.actor._
import storrent.Torrent
import storrent.client.TorrentManager.{ TorrentStarted, StartSeeding, StartTorrent, TorrentIgnored }

import scala.util.{ Failure, Success }

object TorrentManager {

  case class StartSeeding(path: String,
                          trackers: List[String],
                          info: Map[String, Any])

  case class StartTorrent(metainfoData: Array[Byte])

  case class TorrentStarted(client: ActorRef)

  case object TorrentIgnored

}

class TorrentManager(torrentStore: ActorRef) extends Actor with ActorLogging {

  val torrentActors = scala.collection.mutable.HashMap[String, ActorRef]()

  def createTorrentClient(torrent: Torrent): ActorRef = {
    val client = context.actorOf(Props(new TorrentClient(torrent)))
    context.watch(client)
    client
  }

  override def receive: Receive = {
    case StartSeeding(path, trackers, info) =>
    //val metainfo = makeTorrentMetainfo()
    // store(metainfo.infoHash, metainfo)
    // startTorrent(metainfo)

    case StartTorrent(metainfoData) =>
      Torrent(metainfoData) match {
        case Success(metainfo) =>
          val infoHash = metainfo.infoHash
          val client = torrentActors
            .getOrElseUpdate(infoHash, createTorrentClient(metainfo))
          sender ! TorrentStarted(client)
        case Failure(e) =>
          log.info("Ignore invalid torrent metainfo: {}", e.getMessage)
          sender() ! TorrentIgnored
      }

    case Terminated(client) =>
      log.info("Got Terminated({})", client)
      torrentActors.retain((_, v) => v != client)
  }
}
