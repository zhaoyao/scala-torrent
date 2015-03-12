package storrent.tracker.store

import storrent.Torrent
import storrent.tracker.TorrentStore

import scala.concurrent.Future

class InMemoryTorrentStore extends TorrentStore {

  val _store = scala.collection.mutable.HashMap[String, Torrent]()

  override def get(infoHash: String): Future[Option[Torrent]] = Future.successful(_store.get(infoHash))

  override def put(torrent: Torrent): Unit = _store.put(torrent.infoHash, torrent)
}
