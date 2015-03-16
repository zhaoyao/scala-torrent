package storrent.pwp

import java.nio.ByteBuffer

import akka.util.ByteString
import storrent.Util

object Handshake {

  def parse(data: ByteString): (Handshake, ByteString) = {
    val b = ByteBuffer.wrap(data.toArray)
    val protocolLength = b.get()

    val protocol = new Array[Byte](protocolLength)
    val infoHash = new Array[Byte](20)
    val peerId = new Array[Byte](20)

    b.get(protocol)

    // Reserved 8 bytes
    b.position(b.position() + 8)

    b.get(infoHash)
    b.get(peerId)

//    assert(b.remaining() == 0)

    (Handshake(Util.encodeHex(infoHash), new String(peerId, "ISO-8859-1"), new String(protocol, "ISO-8859-1")),
      data.drop(1 + protocolLength + 8 + 40))
  }


}

/**
 * ----------------------------------------------------------------
 * | Name Length | Protocol Name | Reserved | Info Hash | Peer ID |
 * ----------------------------------------------------------------
 */
case class Handshake(infoHash: String, peerId: String, protocol: String = "BitTorrent protocol") {
  require(protocol.length < 256)

  def encode: Array[Byte] = {
    val b = ByteBuffer.allocate(1 + protocol.length + 8 /*reserved*/ + 20 /*info hash*/ + 20 /*peer id*/)

    // name length
    b.put(protocol.length.toByte)

    b.put(protocol.getBytes("ISO-8859-1"))

    // Reserved 8 bytes
    b.position(b.position() + 8)

    b.put(Util.decodeHex(infoHash))

    b.put(peerId.getBytes())

    b.array()
  }

}
