package storrent.extension

import java.nio.ByteBuffer

import sbencoding.DefaultBencodingProtocol
import storrent.pwp.Message

import scala.collection.mutable
import scala.util.{ Try, Failure, Success }

object bep_10 {

  val MsgExtended: Byte = 0x20

  trait Extended extends Message {
    def id: Byte = MsgExtended
  }

  /**
   *
   * @param port Local TCP listen port, Note that there is no need for
   *             the receiving side of the connection to send this extension message
   * @param reqq the number of outstanding request messages this client supports without dropping any
   */
  case class Handshake(extensions: Map[String, Int],
                       port: Option[Int] = None,
                       clientName: Option[String] = None,
                       ip: Option[String] = None,
                       ipv4: Option[Array[Byte]] = None,
                       ipv6: Option[Array[Byte]] = None,
                       reqq: Option[Int] = Some(250) /* libtorrent default value*/ ) extends Extended {

  }

  implicit object BencodingProtocol extends DefaultBencodingProtocol {

    implicit val handshakeFormat = bencodingFormat(Handshake, "m", "p", "v", "yourip", "ipv4", "ipv6", "reqq")

  }

  trait MessageDecoder {
    def supportMessageId(id: Byte): Boolean

    def parse(id: Byte, payload: ByteBuffer): Extended
  }

  class HandshakeMessageDecoder extends MessageDecoder {
    override def supportMessageId(id: Byte): Boolean = id == 0

    override def parse(id: Byte, payload: ByteBuffer): Extended = {
      import sbencoding._
      import BencodingProtocol._

      payload.parseBencoding.convertTo[Handshake]
    }
  }

}

class bep_10 extends HandshakeEnabled with AdditionalMessageDecoding {

  import storrent.extension.bep_10._

  val registeredMessages = mutable.HashSet[MessageDecoder](new HandshakeMessageDecoder())

  def enable(decoder: MessageDecoder) = registeredMessages += decoder

  override def reservedBit: ReservedBit = ReservedBit(5, 0x10)

  override def parseMessage: PartialFunction[(Byte, ByteBuffer), Try[Message]] = {

    case (MsgExtended, payload) =>
      val extendedMsgId = payload.get()
      Try(registeredMessages
        .find(_.supportMessageId(extendedMsgId))
        .map(decoder => decoder.parse(extendedMsgId, payload.slice()))
        .getOrElse(throw new RuntimeException("Unable to parse extended message: " + extendedMsgId)))

  }
}
