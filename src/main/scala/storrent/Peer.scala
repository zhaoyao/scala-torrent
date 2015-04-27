package storrent

import java.net.InetAddress
import java.nio.{ ByteOrder, ByteBuffer }

import sbencoding.DefaultBencodingProtocol

object Peer {

  def parseCompact(data: Array[Byte]): Peer = {
    val (ip, port) = Util.parseCompactIpAndPort(data)
    Peer("", ip, port)
  }

  object BencodingProtocol extends DefaultBencodingProtocol {

    import sbencoding._

    implicit val peerFormat = new BencodingFormat[Peer] {
      override def write(obj: Peer): BcValue =
        BcDict("id" -> BcString(obj.id, "UTF-8"),
          "ip" -> BcString(obj.ip, "UTF-8"),
          "port" -> BcInt(obj.port))

      override def read(bencoding: BcValue): Peer = {
        bencoding.asBcDict.getFields("id", "ip", "port") match {
          case Seq(BcString(id), BcString(ip), BcInt(port)) =>
            Peer(new String(id), new String(ip), port.toInt)
          case x => deserializationError("Invalid peer dict" + x)
        }
      }
    }

    implicit val compactPeerFormat = new BencodingFormat[Peer] {
      override def write(obj: Peer): BcValue = BcString(obj.compact)
      override def read(value: BcValue): Peer = value match {
        case BcString(data) => Peer.parseCompact(data)
        case x              => deserializationError("Invalid compact peer, got " + x.getClass.getSimpleName)
      }
    }

  }

}

case class Peer(id: String, ip: String, port: Int) {

  def compact: Array[Byte] = Util.compactIpAndPort(ip, port)

}

