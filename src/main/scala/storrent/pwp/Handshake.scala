package storrent.pwp

import java.nio.ByteBuffer

import akka.util.ByteString
import storrent.Util
import storrent.pwp.Handshake.ReservedBit

import scala.Predef

object Handshake {

  case class ReservedBit(i: Int, value: Byte)
  object ReservedBits {
    val BEP_6_FastExtension = ReservedBit(7, 0x04)
    val BEP_10_ExtensionProtocol = ReservedBit(5, 0x10)
  }

  def parse(data: ByteString): (Handshake, ByteString) = {
    val b = ByteBuffer.wrap(data.toArray)
    val protocolLength = b.get()

    val protocol = new Array[Byte](protocolLength)
    val infoHash = new Array[Byte](20)
    val peerId = new Array[Byte](20)
    val reserved = new Array[Byte](8)

    b.get(protocol)

    // Reserved 8 bytes
    b.get(reserved)

    b.get(infoHash)
    b.get(peerId)

    //    assert(b.remaining() == 0)

    val hs = Handshake(Util.encodeHex(infoHash),
      new String(peerId, "ISO-8859-1"),
      reserved,
      new String(protocol, "ISO-8859-1"))
    (hs,
      data.drop(1 + protocolLength + 8 + 40))
  }

}

/**
 * ----------------------------------------------------------------
 * | Name Length | Protocol Name | Reserved | Info Hash | Peer ID |
 * ----------------------------------------------------------------
 */
case class Handshake(infoHash: String,
                     peerId: String,
                     reserved: Array[Byte] = Array(0, 0, 0, 0, 0, 0, 0, 0),
                     protocol: String = "BitTorrent protocol") {
  require(reserved.length == 8)
  require(protocol.length < 256)

  def has(bit: ReservedBit) = (reserved(bit.i) & bit.value) == 1

  def encode: Array[Byte] = {
    val b = ByteBuffer.allocate(1 + protocol.length + 8 /*reserved*/ + 20 /*info hash*/ + 20 /*peer id*/ )

    // name length
    b.put(protocol.length.toByte)

    b.put(protocol.getBytes("ISO-8859-1"))

    // Reserved 8 bytes
    b.put(reserved)

    b.put(Util.decodeHex(infoHash))

    b.put(peerId.getBytes())

    b.array()
  }

}
