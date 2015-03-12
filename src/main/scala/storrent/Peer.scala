package storrent

import java.io.FileOutputStream
import java.net.{Inet4Address, InetAddress}
import java.nio.ByteBuffer

import io.netty.handler.codec.socks.SocksAddressType


case class Peer(id: String, ipPort: Option[(String, Int)]) {

  /**
  *  The first 4 bytes contain the 32-bit ipv4 address.
  *  The remaining two bytes contain the port number.
  *  Both address and port use network-byte order.
  *  http://www.bittorrent.org/beps/bep_0023.html
  */
  def compact: Option[Array[Byte]] = {
    ipPort.map { pair =>
      val result = Array.fill[Byte](6)(0)

      pair._1.split('.').map(_.toByte).copyToArray(result)

      result(4) = ((pair._2 >> 8) & 0xff).toByte
      result(5) = ((pair._2) & 0xff).toByte

      result
    }
  }


}

