package storrent.client

import akka.actor.{ActorRef, Actor}
import akka.actor.Actor.Receive
import storrent.client.TorrentClientActor.StartSeeding

object TorrentClientActor {

  case class StartSeeding(path: String,
                          trackers: List[String],
                          info: Map[String, Any])

}

class TorrentClientActor(torrentStore: ActorRef) extends Actor {

  override def receive: Receive = {
    case StartSeeding(path, trackers, info) =>
      //val metainfo = makeTorrentMetainfo()
      // store(metainfo.infoHash, metainfo)
      // startTorrent(metainfo)
  }
}
