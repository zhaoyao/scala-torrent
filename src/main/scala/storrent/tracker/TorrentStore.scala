package storrent.tracker

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import storrent.Torrent
import storrent.tracker.store.InMemoryTorrentStore

import scala.concurrent.Future

/**
 * User: zhaoyao
 * Date: 3/5/15
 * Time: 17:21
 */
trait TorrentStore {

  def get(infoHash: String): Future[Option[Torrent]]

  def put(torrent: Torrent)

}

object TorrentStoreActorMessages {

  case class StoreTorrent(torrent: Torrent)
  case class GetTorrent(infohash: String)

}

object TorrentStoreActor {

  val actorName = "torrent-store"

  def start(system: ActorSystem): ActorRef = {
    system.actorOf(Props(new TorrentStoreActor(new InMemoryTorrentStore())))
  }

}

class TorrentStoreActor(store: TorrentStore) extends Actor {

  import context.dispatcher
  
  override def receive: Receive = {
    case StoreTorrent(t) => store.put(t)
    case GetTorrent(infohash) => pipe(store.get(infohash)).to(sender())
  }

}