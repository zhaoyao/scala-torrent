package storrent.pwp

import java.nio.ByteBuffer

trait Message {
  def id: Byte
  def payloadLength: Int = 0
  def fillPayload(b: ByteBuffer) {}

  def encode: ByteBuffer = {
    val length = 4 + 1 + payloadLength
    val buffer = ByteBuffer.allocate(length)

    buffer.putInt(length - 4) // exclude length header itself
    if (length > 4) {
      buffer.put(this.id)
    }
    fillPayload(buffer)
    buffer.rewind()
    buffer
  }
}

object Message {

  val MsgChoke: Byte = 0x0
  val MsgUnchoke: Byte = 0x1
  val MsgInterested: Byte = 0x2
  val MsgUninterested: Byte = 0x3
  val MsgHave: Byte = 0x4
  val MsgBitfield: Byte = 0x5
  val MsgRequest: Byte = 0x6
  val MsgPiece: Byte = 0x7
  val MsgCancel: Byte = 0x8

  abstract class MessageBase(val id: Byte) extends Message

  trait StateOriented
  trait DataOriented

  val ZeroLengthMessage = ByteBuffer.allocate(4)
  /**
   * A message of size 0.
   */
  case object Keepalive extends Message with StateOriented {
    override def id: Byte = -1
    override def encode: ByteBuffer = ZeroLengthMessage
    override def payloadLength: Int = -1 /* 抵消id的一字节长度 */
    override def fillPayload(b: ByteBuffer): Unit = {}
  }

  /**
   * A peer sends this message to a remote peer to inform the remote peer that it is being choked.
   */
  case object Choke extends MessageBase(MsgChoke) with StateOriented

  /**
   * A peer sends this message to a remote peer to inform the remote peer that it is no longer being choked.
   */
  case object Unchoke extends MessageBase(MsgUnchoke) with StateOriented

  /**
   * A peer sends this message to a remote peer to inform the remote peer of its desire to request data.
   */
  case object Interested extends MessageBase(MsgInterested) with StateOriented

  /**
   * A peer sends this message to a remote peer to inform it that it is not interested in any pieces from the remote peer.
   */
  case object Uninterested extends MessageBase(MsgUninterested) with StateOriented

  /**
   * The payload is a number denoting the index of a piece that
   * the peer has successfully downloaded and validated.
   *
   * A peer receiving this message must validate the index
   * and drop the connection if this index is not within the expected bounds.
   *
   * Also, a peer receiving this message MUST send an interested message to the sender
   * if indeed it lacks the piece announced.
   *
   * Further, it MAY also send a request for that piece.
   */
  case class Have(pieceIndex: Int) extends MessageBase(MsgHave) with StateOriented {
    override def payloadLength: Int = 4
    override def fillPayload(b: ByteBuffer): Unit = b.putInt(pieceIndex)
  }

  /**
   * The payload is a bitfield representing the pieces that the sender has successfully downloaded,
   * with the high bit in the first byte corresponding to piece index 0.
   *
   * If a bit is cleared it is to be interpreted as a missing piece.
   *
   * A peer MUST send this message immediately after the handshake operation,
   * and MAY choose not to send it if it has no pieces at all.
   *
   * This message MUST not be sent at any other time during the communication.
   */
  case class Bitfield(pieceSet: Set[Int]) extends MessageBase(MsgBitfield) with StateOriented {
    override def payloadLength: Int = if (pieceSet.isEmpty) 1 else (pieceSet.max / 8) + 1
    override def fillPayload(b: ByteBuffer): Unit = {
      val bitfield = Array.fill[Byte](payloadLength)(0)
      pieceSet.foreach { pieceIndex =>
        var pieceMark: Int = bitfield(pieceIndex / 8)
        pieceMark |= (1 << (8 - pieceIndex % 8)) & 0xffffffff
        bitfield(pieceIndex / 8) = pieceMark.toByte
      }
      b.put(bitfield)
    }

  }

  /**
   * The payload is 3 integer values indicating a block within a piece
   * that the sender is interested in downloading from the recipient.
   *
   * The recipient MUST only send piece messages to a sender
   * that has already requested it,
   * and only in accordance to the rules given above about the choke and interested states.
   *
   * The payload has the following structure:
   * ---------------------------------------------
   * | Piece Index | Block Offset | Block Length |
   * ---------------------------------------------
   */
  case class Request(pieceIndex: Int, blockOffset: Int, blockLength: Int) extends MessageBase(MsgRequest) with DataOriented {
    override def payloadLength: Int = 3 * 4

    override def fillPayload(b: ByteBuffer): Unit = {
      b.putInt(pieceIndex)
      b.putInt(blockOffset)
      b.putInt(blockLength)
    }
  }

  /**
   * The payload holds 2 integers indicating from which piece and
   * with what offset the block data in the 3rd member is derived.
   *
   * Note, the data length is implicit and can be calculated by subtracting 9
   * from the total message length.
   *
   * The payload has the following structure:
   * -------------------------------------------
   * | Piece Index | Block Offset | Block Data |
   * -------------------------------------------
   */
  case class Piece(pieceIndex: Int, blockOffset: Int, blockData: Array[Byte]) extends MessageBase(MsgPiece) with DataOriented {
    override def payloadLength: Int = 2 * 4 + blockData.length

    override def fillPayload(b: ByteBuffer): Unit = {
      b.putInt(pieceIndex)
      b.putInt(blockOffset)
      b.put(blockData)
    }
  }

  /**
   * The payload is 3 integer values indicating a block within a piece that the sender has requested for,
   * but is no longer interested in.
   *
   * The recipient MUST erase the request information upon receiving this messages.
   *
   * The payload has the following structure:
   * ---------------------------------------------
   * | Piece Index | Block Offset | Block Length |
   * ---------------------------------------------
   */
  case class Cancel(pieceIndex: Int, blockOffset: Int, blockLength: Int) extends MessageBase(MsgCancel) with DataOriented {
    override def payloadLength: Int = 3 * 4

    override def fillPayload(b: ByteBuffer): Unit = {
      b.putInt(pieceIndex)
      b.putInt(blockOffset)
      b.putInt(blockLength)
    }
  }

}
