package storrent

import java.net.InetAddress
import java.nio.{ ByteOrder, ByteBuffer }

import sbencoding.DefaultBencodingProtocol

object Peer {

  def parseCompact(data: Array[Byte]): Peer = {
    val b = ByteBuffer.wrap(data)

    val addrData = new Array[Byte](4)
    b.get(addrData)
    val addr = InetAddress.getByAddress(addrData)

    Peer("",
      addr.getHostAddress,
      (b.getShort() & 0xffff).toInt
    )
  }

  implicit object BencodingProtocol extends DefaultBencodingProtocol {

    import sbencoding._

    def peerFormat = new BencodingFormat[Peer] {
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

  }

}

case class Peer(id: String, ip: String, port: Int) {

  /**
   *  The first 4 bytes contain the 32-bit ipv4 address.
   *  The remaining two bytes contain the port number.
   *  Both address and port use network-byte order.
   *  http://www.bittorrent.org/beps/bep_0023.html
   */
  def compact: Array[Byte] = {
    val result = ByteBuffer.allocate(6)
    result.put(InetAddress.getByName(ip).getAddress)
    result.putShort(port.toShort)
    result.array()
  }

}

