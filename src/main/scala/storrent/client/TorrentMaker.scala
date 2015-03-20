package storrent.client

import java.io.{ File, FileInputStream }
import java.security.MessageDigest

import storrent.Torrent

import scala.concurrent.Future

object TorrentMaker {

  val PieceLen = 2 ^ 18

  def calcFileMd5(file: File): Array[Byte] = {
    val md = MessageDigest.getInstance("MD5")

    val is: FileInputStream = new FileInputStream(file)

    try {
      val b = new Array[Byte](1024 * 1024)
      var len = is.read(b)
      while (len > 0) {
        md.update(b, 0, len)
        len = is.read(b)
      }
    } finally {
      is.close()
    }

    md.digest()
  }

  def make(path: String,
           trackers: List[String],
           info: Map[String, Any],
           wantMd5Sum: Boolean = false): Future[Torrent] = {

    null
  }

}
