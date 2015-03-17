package storrent.pwp

import java.nio.ByteBuffer

import akka.util.ByteString

import scala.collection.mutable.ArrayBuffer

class MessageTooLargeException extends RuntimeException

object MessageDecoder {

  val State_WANT_LENGTH = 0
  val State_WANT_MSG_ID = 1
  val State_WANT_PAYLOAD = 2

}

/**
 * ** NOT THREAD SAFE
 */
class MessageDecoder(maxMessageLength: Int = 1024 * 1024 * 5) {

  import storrent.pwp.MessageDecoder._

  var state = State_WANT_LENGTH

  val lengthBuffer = ByteBuffer.allocate(4)
  var payloadBuffer: ByteBuffer = null

  var msgLength = -1
  var msgId: Byte = -1;

  def reset() = {
    state = State_WANT_LENGTH
    lengthBuffer.rewind()
    payloadBuffer = null
    msgId = -1
    msgLength = -1
  }

  import storrent.pwp.Message._

  private def decodeMessage(): Message = {
    assert(payloadBuffer == null || payloadBuffer.remaining() == 0)
    if (payloadBuffer != null) {
      payloadBuffer.rewind()
    }

    val msg = msgId match {
      case MsgChoke        => Choke
      case MsgUnchoke      => Unchoke
      case MsgInterested   => Interested
      case MsgUninterested => Uninterested
      case MsgHave         => Have(payloadBuffer.getInt)
      case MsgBitfield => {
        val pieces = ArrayBuffer[Int]()
        for (i <- 0 until payloadBuffer.limit) {
          val mark = payloadBuffer.get(i)
          for (bit <- 0 until 8) {
            if (((mark & 0x01 << bit) >> bit) == 1) {
              pieces += i * 8 + (8 - bit)
            }
          }
        }
        Bitfield(pieces.toSet)
      }
      case MsgRequest =>
        Request(payloadBuffer.getInt, payloadBuffer.getInt, payloadBuffer.getInt)
      case MsgPiece =>
        val pieceIndex = payloadBuffer.getInt
        val blockOffset = payloadBuffer.getInt
        val blockLength = payloadBuffer.limit() - 8
        val block = new Array[Byte](blockLength)
        payloadBuffer.get(block)
        Piece(pieceIndex, blockOffset, block)
      case MsgCancel =>
        Cancel(payloadBuffer.getInt, payloadBuffer.getInt, payloadBuffer.getInt)
    }

    reset()
    msg
  }

  def decode(data: ByteString): (Option[Message], ByteString) = {
    state match {
      case State_WANT_LENGTH =>
        val lengthNeed = 4 - lengthBuffer.position()
        val lengthHave = Math.min(data.length, lengthNeed)
        lengthBuffer.put(data.take(lengthHave).toArray)

        if (lengthNeed == lengthHave) {
          this.lengthBuffer.rewind()
          this.msgLength = lengthBuffer.getInt
          state = State_WANT_MSG_ID
          if (msgLength > 1) {
            payloadBuffer = ByteBuffer.allocate(this.msgLength - 1)
          }
          return decode(data.drop(4))
        } else {
          return (None, ByteString.empty)
        }

      case State_WANT_MSG_ID =>
        if (this.msgLength == 0) {
          //keepalive
          reset()
          return (Some(Keepalive), data)

        } else {
          if (data.length >= 1) {
            this.msgId = data(0)
            state = State_WANT_PAYLOAD
            return decode(data.drop(1))
          } else {
            return (None, ByteString.empty)
          }
        }

      case State_WANT_PAYLOAD =>
        if (this.msgLength == 1) {
          // no payload
          return (Some(decodeMessage()), data)

        } else {
          val payloadNeed = msgLength - 1 - payloadBuffer.position()
          val payloadHave = Math.min(data.length, payloadNeed)

          payloadBuffer.put(data.slice(0, payloadHave).toArray)
          if (payloadNeed == payloadHave) {
            // complete message decoding
            return (Some(decodeMessage()), data.drop(payloadHave))
          } else {
            return (None, ByteString.empty)
          }

        }
    }
  }

}
