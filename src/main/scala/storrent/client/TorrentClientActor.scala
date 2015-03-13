package storrent.client

import akka.actor.{ActorRef, Actor}
import akka.actor.Actor.Receive
import storrent.Torrent
import storrent.client.TorrentClientActor.{StartTorrent, StartSeeding}

object TorrentClientActor {

  case class StartSeeding(path: String,
                          trackers: List[String],
                          info: Map[String, Any])

  case class StartTorrent(metainfoData: String)

}

class TorrentClientActor(torrentStore: ActorRef) extends Actor {

  override def receive: Receive = {
    case StartSeeding(path, trackers, info) =>
      //val metainfo = makeTorrentMetainfo()
      // store(metainfo.infoHash, metainfo)
      // startTorrent(metainfo)

  }
}
