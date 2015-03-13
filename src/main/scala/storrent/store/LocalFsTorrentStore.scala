package storrent.store

import java.io.{File, FileOutputStream}
import java.nio.file.Paths

import akka.actor.Actor
import storrent.Torrent

import scala.concurrent.Future
import scala.io.Source

/**
 * User: zhaoyao
 * Date: 3/12/15
 * Time: 13:35
 */
class LocalFsTorrentStore(storeDir: String) extends TorrentStore {
  _: Actor =>

  import context.dispatcher

  def getTorrentData(infoHash: String) = {
    val f = torrentFile(infoHash)

    f.exists() match {
      case true => Some(Source.fromFile(f).mkString)
      case false => None
    }
  }

  def torrentFile(infoHash: String): File = {
    Paths.get(storeDir,
      infoHash.substring(0, 2),
      infoHash.substring(2, 4),
      s"$infoHash.torrent").toFile
  }

  override def get(infoHash: String): Future[Option[Torrent]] = {
    Future {
      getTorrentData(infoHash).flatMap(v => Torrent(v).toOption)
    }
  }

  override def put(torrent: Torrent): Future[Boolean] = {
    val f = torrentFile(torrent.info.hash)
    if (!f.exists()) {
      f.getParentFile.mkdirs()
      if (!f.createNewFile()) {
        return Future.successful(false)
      }
    }
    Future {
      val out = new FileOutputStream(f)
      try {
        out.write(torrent.toBencoding.getBytes)
        true
      } finally {
        out.close
      }
    }
  }
}
