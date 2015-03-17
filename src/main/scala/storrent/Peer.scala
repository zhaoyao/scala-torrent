package storrent

import java.net.InetAddress
import java.nio.ByteBuffer

object Peer {

  def parseCompact(data: Array[Byte]): Peer = {
    val b = ByteBuffer.wrap(data)

    val addrData = new Array[Byte](4)
    b.get(addrData)
    val addr = InetAddress.getByAddress(addrData)

    Peer("", addr.getHostAddress, b.getShort())
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

