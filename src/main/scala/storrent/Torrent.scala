package storrent

import storrent.bencode.BencodeDecoder

import scala.util.{Failure, Try}

object Torrent {

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
    null
  }

}

class Torrent private (fields: Map[String, Any]) {

  def infoHash: String = fields("info_hash").asInstanceOf[String]

}
