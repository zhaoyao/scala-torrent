package storrent.pwp

import java.nio.ByteBuffer

import akka.util.ByteString
import com.typesafe.scalalogging.{ StrictLogging, Logger }
import storrent.Slf4jLogging
import storrent.extension.AdditionalMessageDecoding
import storrent.extension.bep_10.Extended

import scala.collection.mutable.ArrayBuffer
import scala.util.{ Success, Try }

class MessageTooLargeException extends RuntimeException

object MessageDecoder {

  val State_WANT_LENGTH = 0
  val State_WANT_MSG_ID = 1
  val State_WANT_PAYLOAD = 2

}

/**
 * ** NOT THREAD SAFE
 */
class MessageDecoder(extensions: Set[AdditionalMessageDecoding] = Set.empty,
                     maxMessageLength: Int = 1024 * 1024 * 5) extends StrictLogging {

  import storrent.pwp.MessageDecoder._

  var state = State_WANT_LENGTH

  val lengthBuffer = ByteBuffer.allocate(4)
  var payloadBuffer: ByteBuffer = null

  var msgLength = -1
  var msgId: Byte = -1

  def reset() = {
    state = State_WANT_LENGTH
    lengthBuffer.rewind()
    payloadBuffer = null
    msgId = -1
    msgLength = -1
  }

  import storrent.pwp.Message._

  val builtInMessages: PartialFunction[(Byte, ByteBuffer), Try[Message]] = {
    case (MsgChoke, _)        => Success(Choke)
    case (MsgUnchoke, _)      => Success(Unchoke)
    case (MsgInterested, _)   => Success(Interested)
    case (MsgUninterested, _) => Success(Uninterested)
    case (MsgHave, _)         => Success(Have(payloadBuffer.getInt))
    case (MsgBitfield, payload) =>
      val pieces = ArrayBuffer[Int]()
      for (i <- 0 until payload.limit) {
        val mark = payload.get(i)
        for (bit <- 0 until 8) {
          if (((mark & 0x01 << bit) >> bit) == 1) {
            pieces += i * 8 + (7 - bit)
          }
        }
      }
      Success(Bitfield(pieces.toSet))
    case (MsgRequest, payload) =>
      Success(Request(payload.getInt, payload.getInt, payload.getInt))
    case (MsgPiece, payload) =>
      val pieceIndex = payload.getInt
      val blockOffset = payload.getInt
      val blockLength = payload.limit() - 8
      val block = new Array[Byte](blockLength)
      payload.get(block)
      Success(Piece(pieceIndex, blockOffset, block))
    case (MsgCancel, payload) =>
      Success(Cancel(payload.getInt, payload.getInt, payload.getInt))
  }

  val decodeMessage0 = extensions.foldLeft(builtInMessages) { (s, d) => s.orElse(d.parseMessage) }

  private def decodeMessage(): Option[Message] = {
    assert(payloadBuffer == null || payloadBuffer.remaining() == 0)
    if (payloadBuffer != null) {
      payloadBuffer.rewind()
    }

    if (decodeMessage0.isDefinedAt((this.msgId, this.payloadBuffer))) {
      val ret = decodeMessage0((this.msgId, this.payloadBuffer))
      reset()
      ret.toOption
    } else {
      //TODO handle unknown message logging or throw error?
      logger.info(s"Receive unknown message. id=$msgId")
      reset()
      None
    }
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
          decode(data.drop(4))
        } else {
          (None, ByteString.empty)
        }

      case State_WANT_MSG_ID =>
        if (this.msgLength == 0) {
          //keepalive
          reset()
          (Some(Keepalive), data)

        } else {
          if (data.length >= 1) {
            this.msgId = data.head
            state = State_WANT_PAYLOAD
            decode(data.drop(1))
          } else {
            (None, ByteString.empty)
          }
        }

      case State_WANT_PAYLOAD =>
        if (this.msgLength == 1) {
          // no payload
          (decodeMessage(), data)

        } else {
          require(payloadBuffer != null, s"payloadBuffer is null while id=$msgId msgLength=$msgLength")

          val payloadNeed = msgLength - 1 - payloadBuffer.position()
          val payloadHave = Math.min(data.length, payloadNeed)

          payloadBuffer.put(data.slice(0, payloadHave).toArray)
          if (payloadNeed == payloadHave) {
            // complete message decoding
            (decodeMessage(), data.drop(payloadHave))
          } else {
            (None, ByteString.empty)
          }

        }
    }
  }

}
