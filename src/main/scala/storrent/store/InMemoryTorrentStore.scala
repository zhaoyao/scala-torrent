package storrent.store

import storrent.Torrent

import scala.concurrent.Future

class InMemoryTorrentStore extends TorrentStore {

  val _store = scala.collection.mutable.HashMap[String, Torrent]()

  override def get(infoHash: String): Future[Option[Torrent]] = Future.successful(_store.get(infoHash))

  override def put(torrent: Torrent): Future[Boolean] = {
    _store.put(torrent.info.hash, torrent)
    Future.successful(true)
  }
}
