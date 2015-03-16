package storrent.pwp

import java.nio.ByteBuffer

import org.scalatest.{Matchers, WordSpec}

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 23:40
 */
class MessageEncodeSpec extends WordSpec with Matchers with MessageSpecBase {

  import storrent.pwp.Message._


  "A peer wire message" should {

    "Encode Keepalive properly" in {
      Keepalive.encode shouldEqual ByteBuffer.wrap(Array[Byte](0, 0, 0, 0))
    }

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
      Have(1).encode shouldEqual encodedMsg(MsgHave, 4) { b =>
        b.putInt(1)
      }
    }

    "Encode 'Bitfield properly" in {
      Bitfield(Set(1, 3, 5, 7, 9)).encode shouldEqual encodedMsg(MsgBitfield, 2) { b =>
        b.put(Integer.parseInt("10101010", 2).toByte)
        b.put(Integer.parseInt("10000000", 2).toByte)
      }

    }

    "Encode 'Request properly" in {
      Request(1, 0, 256).encode shouldEqual
        encodedMsg(MsgRequest, 12) { b =>
          b.putInt(1)
          b.putInt(0)
          b.putInt(256)
        }
    }

    "Encode 'Piece properly" in {
      Piece(1, 0, Array[Byte](1, 2, 3)).encode shouldEqual
        encodedMsg(MsgPiece, 11) { b =>
          b.putInt(1).putInt(0).put(Array[Byte](1, 2, 3))
        }
    }

    "Encode 'Cancel properly" in {
      Cancel(1, 0, 256).encode shouldEqual
        encodedMsg(MsgCancel, 12) { b =>
          b.putInt(1)
            .putInt(0)
            .putInt(256)
        }
    }
  }


}
