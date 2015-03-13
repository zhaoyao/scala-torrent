package storrent

import java.security.MessageDigest

import storrent.TorrentFiles.TorrentFile
import storrent.bencode.{BencodeDecoder, BencodeEncoder}

import scala.util.{Failure, Try}

object Torrent {

  // 兼容bitcomet
  def preferUtf8String(fields: Map[String, Any], key: String) =
    fields.get(s"$key.utf-8")
      .orElse(fields.get(key)).asInstanceOf[Option[String]]

  /**
    * 解析原始bencode数据
    */
  def apply(data: String): Try[Torrent] = {
    BencodeDecoder.decode(data).flatMap {
      case fields: Map[_, _] => apply(fields.asInstanceOf[Map[String, Any]])
      case _ => Failure(new MalformedTorrentException)
    }
  }

  def apply(fields: Map[String, Any]): Try[Torrent] = Try(validate(fields))

  private def validate(data: Map[String, Any]): Torrent = {
    require(data.contains("announce"), "no announce field present")
    require(data.contains("info"), "no info dict field present")

    val info = data("info").asInstanceOf[Map[String, Any]]
    require(info.contains("piece length"))
    require(info.contains("pieces"))

    require(info("pieces").asInstanceOf[String].length % 20 == 0)

    new Torrent(data)
  }

}

object InfoDict {
  def apply(fields: Map[String, Any]): InfoDict = new InfoDict(fields)
}

class InfoDict(fields: Map[String, Any]) {
  val length: Option[Long] = fields.get("length").asInstanceOf[Option[Long]]
  val md5sum: Option[String] = fields.get("md5sum").asInstanceOf[Option[String]]

  val name: String = Torrent.preferUtf8String(fields, "name").getOrElse("")

  val pieceLength: Long = fields("piece length").asInstanceOf[Long]
  val pieces: List[String] = fields("pieces").asInstanceOf[String].foldLeft(List[String](), "") { (p, c) =>
    val (xs, s) = p
    if (s.length == 19) {
      ((s + c) :: xs, "")
    } else {
      (xs, s + c)
    }
  }._1.reverse

  val isPrivate: Boolean = fields.getOrElse("private", 0l).asInstanceOf[Long] == 1l

  val files: Option[List[TorrentFile]] = {
    val fileList = fields.get("files").asInstanceOf[Option[List[Map[String, Any]]]]
    fileList.map { files =>
      files.map { fields =>
        TorrentFile(
          fields("path").asInstanceOf[List[String]].mkString("/"),
          fields("length").asInstanceOf[Long],
          fields.get("md5sum").asInstanceOf[Option[String]].map(_.getBytes)
        )
      }
    }
  }

  val hash: String = Util.sha1(BencodeEncoder.encode(fields))
}

class Torrent private(fields: Map[String, Any]) {

  val announce: String = fields("announce").asInstanceOf[String]
  val announceList: List[String] = fields.getOrElse("announce-list", Nil).asInstanceOf[List[List[String]]].map(_(0))
  val comment: Option[String] = Torrent.preferUtf8String(fields, "comment")
  val publisher: Option[String] = Torrent.preferUtf8String(fields, "publisher")
  val createdBy: Option[String] = fields.get("created by").asInstanceOf[Option[String]]
  val creationDate: Option[Long] = fields.get("creation date").asInstanceOf[Option[Long]]

  val info = InfoDict(fields("info").asInstanceOf[Map[String, Any]])

  def toBencoding = BencodeEncoder.encode(fields)

}
