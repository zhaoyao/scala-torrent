package storrent.pwp

import java.nio.ByteBuffer

import akka.util.ByteString
import org.scalatest.Inside._
import org.scalatest.{ Matchers, WordSpec }

/**
 * User: zhaoyao
 * Date: 3/16/15
 * Time: 15:12
 */
class MessageDecodeSpec extends WordSpec with Matchers with MessageSpecBase {

  import storrent.pwp.Message._

  implicit def byteBuffer2ByteString(b: ByteBuffer) = ByteString(b)

  "Empty MessageDecode with whole message data" should {

    "Decode Keepalive properly" in {
      inside(new MessageDecoder().decode(ByteString(0.toByte, 0.toByte, 0.toByte, 0.toByte))) {
        case (Some(Keepalive), d: ByteString) =>
          d.length shouldEqual 0
      }
    }

    "Decode Choke properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgChoke))) {
        case (Some(Choke), d: ByteString) =>
          d.length shouldEqual 0
      }
    }

    "Decode Interested properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgInterested))) {
        case (Some(Interested), d: ByteString) =>
          d.length shouldEqual 0
      }
    }

    "Decode Uninterested properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgUninterested))) {
        case (Some(Uninterested), d: ByteString) =>
          d.length shouldEqual 0
      }
    }

    "Decode Have properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgHave, 4)(b => b.putInt(1)))) {
        case (Some(h: Have), d: ByteString) =>
          h.pieceIndex shouldEqual 1
          d.length shouldEqual 0
      }
    }

    "Decode Bitfield properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgBitfield, 2)(b => {
        b.put(Integer.parseInt("10101010", 2).toByte)
        b.put(Integer.parseInt("10000000", 2).toByte)
      }))) {
        case (Some(m: Bitfield), d: ByteString) =>
          m.pieceSet shouldEqual Set(0, 2, 4, 6, 8)
          d.length shouldEqual 0

      }
    }

    "Decode Request properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgRequest, 12) { b =>
        b.putInt(1).putInt(0).putInt(522)
      })) {
        case (Some(m: Request), d: ByteString) =>
          m.pieceIndex shouldEqual 1
          m.blockOffset shouldEqual 0
          m.blockLength shouldEqual 522
          d.length shouldEqual 0
      }
    }

    "Decode Piece properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgPiece, 9) { b =>
        b.putInt(1)
          .putInt(10)
          .put(Array[Byte](1.toByte))
      })) {
        case (Some(m: Piece), d: ByteString) =>
          m.pieceIndex shouldEqual 1
          m.blockOffset shouldEqual 10
          m.blockData shouldEqual Array[Byte](1.toByte)
          d.length shouldEqual 0
      }
    }

    "Decode Cancel properly" in {
      inside(new MessageDecoder().decode(encodedMsg(MsgCancel, 12) { b =>
        b.putInt(1).putInt(0).putInt(522)
      })) {
        case (Some(m: Cancel), d: ByteString) =>
          m.pieceIndex shouldEqual 1
          m.blockOffset shouldEqual 0
          m.blockLength shouldEqual 522
          d.length shouldEqual 0
      }
    }

  }

  "Decode insufficient data" should {

    "Not Enough length data" in {
      val decoder = new MessageDecoder()
      inside(decoder.decode(ByteString(0.toByte, 0.toByte))) {
        case (None, d: ByteString) =>
          d.length shouldEqual 0
          decoder.lengthBuffer.position shouldEqual 2
          decoder.msgLength shouldEqual -1
          decoder.state shouldEqual MessageDecoder.State_WANT_LENGTH
      }

      inside(decoder.decode(ByteString(0.toByte, 1.toByte))) {
        case (None, d: ByteString) =>
          d.length shouldEqual 0
          decoder.lengthBuffer.position shouldEqual 4
          decoder.msgLength shouldEqual 1
          decoder.state shouldEqual MessageDecoder.State_WANT_MSG_ID
      }
    }

    "Not Enough payload data" in {
      val decoder = new MessageDecoder()
      inside(decoder.decode(ByteString(0.toByte, 0.toByte))) {
        case (None, d: ByteString) =>
          d.length shouldEqual 0
          decoder.lengthBuffer.position shouldEqual 2
          decoder.msgLength shouldEqual -1
          decoder.state shouldEqual MessageDecoder.State_WANT_LENGTH
      }

      inside(decoder.decode(ByteString(0.toByte, 13.toByte))) {
        case (None, d: ByteString) =>
          d.length shouldEqual 0
          decoder.lengthBuffer.position shouldEqual 4
          decoder.msgLength shouldEqual 13
          decoder.state shouldEqual MessageDecoder.State_WANT_MSG_ID
      }

      inside(decoder.decode(ByteString(MsgRequest, 0, 0))) {
        case (None, d: ByteString) =>
          d.length shouldEqual 0
          decoder.msgId shouldEqual MsgRequest
          decoder.payloadBuffer.limit() shouldEqual 12
          decoder.payloadBuffer.position shouldEqual 2
          decoder.state shouldEqual MessageDecoder.State_WANT_PAYLOAD
      }

      inside(decoder.decode(ByteString(Array[Byte](0, 1, 0, 0, 0, 1, 0, 0)))) {
        case (None, d: ByteString) =>
          d.length shouldEqual 0
          decoder.state shouldEqual MessageDecoder.State_WANT_PAYLOAD

          decoder.payloadBuffer.position shouldEqual 10
      }

      inside(decoder.decode(ByteString(Array[Byte](0, 1)))) {
        case (Some(Request(1, 1, 1)), d: ByteString) =>
          d.length shouldEqual 0
          decoder.state shouldEqual MessageDecoder.State_WANT_LENGTH
          decoder.payloadBuffer shouldBe null
          decoder.lengthBuffer.position shouldBe 0
          decoder.msgId shouldBe -1
          decoder.msgLength shouldBe -1
      }
    }

    "Cross message boundary" in {
      val decoder = new MessageDecoder()

      val buffer = encodedMsg(MsgHave, 12) { b =>
        b.putInt(1)
      }

      val data = new Array[Byte](buffer.limit() + 2)
      buffer.get(data, 0, buffer.limit())

      inside(decoder.decode(ByteString(data))) {
        case (Some(Have(1)), d: ByteString) =>
          d.length shouldEqual 2

          decoder.state shouldEqual MessageDecoder.State_WANT_LENGTH
          decoder.payloadBuffer shouldBe null
          decoder.lengthBuffer.position shouldBe 0
          decoder.msgId shouldBe -1
          decoder.msgLength shouldBe -1
      }
    }
  }
}
