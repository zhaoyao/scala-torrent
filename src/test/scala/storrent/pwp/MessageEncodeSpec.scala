package storrent.pwp

import java.nio
import java.nio.ByteBuffer

import akka.util.ByteString
import org.scalatest.{Matchers, WordSpec, FunSuite}
import storrent.pwp.Message.Choke

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 23:40
 */
class MessageEncodeSpec extends WordSpec with Matchers  {

  import Message._

  def encodedMsg(id: Byte) = {
    ByteBuffer.wrap(Array[Byte](0, 0, 0, 1, id))
  }

  def encodedMsg(id: Byte, payloadLength: Int) = {
    val b = ByteBuffer.allocate(4+1+payloadLength)
    b.putInt(1+payloadLength)
    b.put(id)
    b
  }

  "A peer wire message" should {

    "Encode Choke properly" in {
      Choke.encode shouldEqual encodedMsg(MsgChoke)
    }

    "Encode Unchoke properly" in {
      Unchoke.encode shouldEqual encodedMsg(MsgUnchoke)
    }

    "Encode Interested properly" in {
      Interested.encode shouldEqual encodedMsg(MsgInterested)
    }

    "Encode Uninterested properly" in {
      Uninterested.encode shouldEqual encodedMsg(MsgUninterested)
    }

    "Encode Have properly" in {
      Have(1).encode shouldEqual encodedMsg(MsgHave, 4).putInt(1).rewind()
    }

    "Encode 'Bitfield properly" in {
      Bitfield(Set(1, 3, 5, 7, 9)).encode shouldEqual encodedMsg(MsgBitfield, 2)
        .put(Integer.parseInt("10101010", 2).toByte)
        .put(Integer.parseInt("10000000", 2).toByte)
        .rewind()

    }

    "Encode 'Request properly" in {
      Request(1, 0, 256).encode shouldEqual
        encodedMsg(MsgRequest, 12)
        .putInt(1)
        .putInt(0)
        .putInt(256)
        .rewind()
    }

    "Encode 'Piece properly" in {
      Piece(1, 0, Array[Byte](1, 2, 3)).encode shouldEqual
        encodedMsg(MsgPiece, 11).putInt(1).putInt(0).put(Array[Byte](1, 2, 3)).rewind()
    }

    "Encode 'Cancel properly" in {
      Cancel(1, 0, 256).encode shouldEqual
        encodedMsg(MsgCancel, 12)
          .putInt(1)
          .putInt(0)
          .putInt(256)
          .rewind()
    }

  }
  
  
  
}
