package storrent

import sbencoding._
import storrent.TorrentFiles.TorrentFile

import scala.io.Codec
import scala.util.Try

object Torrent {

  implicit object TorrentBencodingProtocol extends DefaultBencodingProtocol {

    implicit val torrentFileFormat: BencodingFormat[TorrentFile] = new RootBencodingFormat[TorrentFile] {

      override def write(obj: TorrentFile): BcValue = BcDict(
        "path" -> BcList(obj.path.split("/").map(s => BcString(s.getBytes("UTF-8"))).toVector),
        "length" -> BcInt(obj.length),
        "md5sum" -> obj.md5sum.map(BcString(_)).getOrElse(BcNil)
      )

      override def read(value: BcValue): TorrentFile = value.asBcDict.getFields("path", "length", "md5sum") match {
        case Seq(BcString(path), BcInt(length), BcNil) =>
          TorrentFile(new String(path), length, None)

        case Seq(BcString(path), BcInt(length), BcString(md5sum)) =>
          TorrentFile(new String(path), length, Some(md5sum))

        case Seq(BcList(elements), BcInt(length), BcNil) if elements.forall(_.isInstanceOf[BcString]) =>
          val path = elements.map(s => new String(s.asInstanceOf[BcString].value, "UTF-8")).mkString("/")
          TorrentFile(path, length, None)

        case Seq(BcList(elements), BcInt(length), BcString(md5sum)) =>
          val path = elements.map(s => new String(s.asInstanceOf[BcString].value, "UTF-8")).mkString("/")
          TorrentFile(path, length, Some(md5sum))

        case x => deserializationError("invalid file " + x)
      }
    }

    implicit val infoFormat: BencodingFormat[Info] = bencodingFormat(Info,
      "name", "length", "md5sum", "piece length", "pieces", "private", "files", "name.utf-8")

    implicit def metainfoFormat = bencodingFormat(Metainfo,
      "announce", "announce-list", "comment", "publisher", "created by", "creation date", "info")

  }

  /**
   * 解析原始bencode数据
   */
  def apply(data: String)(implicit codec: Codec): Try[Torrent] = apply(data.getBytes(codec.charSet))

  def apply(data: Array[Byte]): Try[Torrent] = {
    import sbencoding._
    import storrent.Torrent.TorrentBencodingProtocol._

    val raw: BcValue = data.parseBencoding
    val metainfo = Try(data.parseBencoding.convertTo[Metainfo])
    metainfo.map(m => new Torrent(m, raw.asBcDict))
  }

}

case class Info(_name: String,
                length: Option[Long],
                md5sum: Option[String],
                pieceLength: Long,
                pieces: String,
                _private: Option[Boolean],
                files: Option[List[TorrentFile]],
                nameUTF8: Option[String]) {

  def name = nameUTF8.getOrElse(_name)

  def isPrivate = _private.getOrElse(false)
}

case class Metainfo(announce: String,
                    announceList: Option[List[List[String]]] = Some(Nil),
                    comment: Option[String],
                    publisher: Option[String],
                    createdBy: Option[String],
                    creationDate: Option[Long],
                    info: Info)

case class Torrent private (metainfo: Metainfo, raw: BcDict) {

  val infoHashRaw = Util.sha1(raw.getFields("info").head.asBcDict().toByteArray())
  val infoHash = Util.encodeHex(infoHashRaw)

}
