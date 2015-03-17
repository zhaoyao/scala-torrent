package storrent.extension

import java.nio.ByteBuffer

import storrent.bencode.BencodeDecoder
import storrent.pwp.Message

import scala.collection.mutable
import scala.reflect.internal.Trees.Try
import scala.util.{Try, Failure, Success}

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
  case class Handshake(enabledExtensions: Map[String, Int],
                       disabledExtensions: Set[String],
                       port: Option[Int] = None,
                       clientName: Option[String] = None,
                       ip: Option[String] = None,
                       ipv6: Option[Array[Byte]] = None,
                       ipv4: Option[Array[Byte]] = None,
                       reqq: Option[Int] = Some(250) /* libtorrent default value*/) extends Extended {

  }

  trait MessageDecoder {
    def supportMessageId(id: Byte): Boolean

    def parse(id: Byte, payload: ByteBuffer): Extended
  }

  class HandshakeMessageDecoder extends MessageDecoder {
    override def supportMessageId(id: Byte): Boolean = id == 0

    override def parse(id: Byte, payload: ByteBuffer): Try[Extended] = {
      var enabledExtensions = Map[String, Int]()
      var disabledExtensions = Set[String]()

      BencodeDecoder.decode(new String(payload.array(),
        payload.arrayOffset(), payload.remaining())).map {
        case map: Map[_, _] =>
          map.asInstanceOf[Map[String, Any]]("v") match {
            case extensionMapping: Map[_, _] =>
              extensionMapping.toList
                .foreach {
                case (ext: String, 0) =>
                  disabledExtensions = disabledExtensions + ext
                case (ext: String, id: Number) =>
                  enabledExtensions = enabledExtensions + (ext -> id.intValue())
              }
          }

          val port = map.asInstanceOf[Map[String, Any]].get("p").asInstanceOf[Option[Long]].map(_.toInt)
          val clientName = map.asInstanceOf[Map[String, Any]].get("v").asInstanceOf[Option[String]]
          val ip = map.asInstanceOf[Map[String, Any]].get("yourip").asInstanceOf[Option[String]]
          val ipv6 = map.asInstanceOf[Map[String, Any]].get("ipv6").asInstanceOf[Option[String]].map(_.getBytes("ISO-8859-1"))
          val ipv4 = map.asInstanceOf[Map[String, Any]].get("ipv4").asInstanceOf[Option[String]].map(_.getBytes("ISO-8859-1"))

          Handshake(enabledExtensions, disabledExtensions,
            port, clientName, ip, ipv6, ipv4)
      }

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
