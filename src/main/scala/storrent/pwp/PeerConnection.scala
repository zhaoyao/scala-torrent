package storrent.pwp

import java.net.InetSocketAddress

import akka.actor._
import akka.io.Tcp._
import akka.io.{ IO, Tcp }
import akka.pattern._
import akka.util.ByteString
import storrent.extension.{ AdditionalMessageDecoding, HandshakeEnabled }
import storrent.pwp.Message._
import storrent.pwp.PeerConnection.Start
import storrent.{ ActorStack, Peer, Slf4jLogging }

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

object PeerConnection {

  def props(infoHash: String, peerId: String, endpoint: Peer, session: ActorRef, inbound: Boolean) =
    Props(classOf[PeerConnection], infoHash, peerId, endpoint, session, inbound, Set.empty)

  case class Start(tcpConn: Option[ActorRef])

  val ConnectTimeout = 2.minutes

}

/**
 * 处理peer连接的actor。
 * 分为两种模式:
 *  1. 被动连接进来的peer (inbound)
 *  2. 主动连接出去的peer (outbound, 从tracker等获取到得)
 *
 *  被动连接进来的peer，需要在外部做好
 */
class PeerConnection(infoHash: String,
                     peerId: String,
                     endpoint: Peer,
                     session: ActorRef,
                     inbound: Boolean,
                     handshakeExtensions: Set[HandshakeEnabled with AdditionalMessageDecoding] = Set.empty)
    extends ActorStack with Slf4jLogging with Stash {

  import context.dispatcher
  import PeerConnection._

  var decoder: MessageDecoder = _
  val handshake: Handshake = Handshake(infoHash, peerId)

  var choked = false

  var interested = false

  val have = mutable.Set[Int]()

  var tcpConn: ActorRef = null
  var requestor: ActorRef = null

  override def postStop(): Unit = {
    if (tcpConn != null) {
      tcpConn ! Close
    }
  }

  override def wrappedReceive: Receive = waitToStart

  def handlePeerClose: Receive = {
    case PeerClosed =>
      logger.debug(s"Peer[$endpoint] connection closed")
      context stop self
  }

  def waitToStart: Receive = {
    case Start(conn) =>
      requestor = sender()
      conn match {
        case Some(c) =>
          attachTcpConn(c)
          context become handshake(requestor)

        case None =>
          logger.debug(s"Creating connection to $endpoint")
          IO(Tcp)(context.system) ! Connect(new InetSocketAddress(endpoint.ip, endpoint.port))

      }

    case _: Connected =>
      val tcpConn = sender()
      attachTcpConn(tcpConn)
      context become handshake(requestor)

    case CommandFailed(_: Connect) =>
      requestor ! false
      context stop self

  }

  def handshake(requestor: ActorRef): Receive = handlePeerClose orElse {
    case Received(data) =>
      Try(Handshake.parse(data)) match {
        case Success((hs: Handshake, remaining: ByteString)) =>
          if ((endpoint.id != "" && hs.peerId != endpoint.id) || hs.protocol != handshake.protocol) {
            logger.info("Invalid handshake: {}. peer={} ih={}", hs, endpoint, infoHash)
            requestor ! false
            context stop self

          } else {
            logger.debug(s"Connection ${if (inbound) "from" else "to"} peer $endpoint established")

            decoder = new MessageDecoder(handshakeExtensions
              .filter(_.isEnabled(hs)).map(_.asInstanceOf[AdditionalMessageDecoding]))

            handleInboundData(remaining)

            context become connected(sender())
            unstashAll()
            context.system.scheduler.schedule(5.seconds, 5.seconds, self, Keepalive)
            requestor ! true
          }

        case Failure(e) =>
          logger.info("Handshake parsing failed: {}", e.getMessage)
          context stop self
          requestor ! false
      }

    case _ => stash()
  }

  def connected(conn: ActorRef): Receive = handlePeerClose orElse {
    case m: Message =>
      //      logger.debug(s"Forwarding[${targetPeer.ip}}] pwp msg: ${m}")
      m match {
        case _: StateOriented =>
          conn ! Write(ByteString(m.encode))

        case _: Piece =>
          if (!interested) {
            logger.info("Drop piece message, cause peer is not intersected.")
          } else {
            conn ! Write(ByteString(m.encode))
          }

        case _: DataOriented =>
          if (choked) {
            logger.info("Drop data oriented message, cause peer is choked.")
          } else {
            conn ! Write(ByteString(m.encode))
          }

        case _ =>
          conn ! Write(ByteString(m.encode))
      }

    case Received(data) =>
      handleInboundData(data)

    case CommandFailed(_: Write) =>
      // log handshake failed
      context stop self
  }

  def handleInboundData(data: ByteString): Unit = {
    decodeMessage(data).foreach {
      case Choke =>
        this.choked = true
        session ! Tuple2(endpoint, Choke)

      case Unchoke =>
        this.choked = false
        session ! Tuple2(endpoint, Unchoke)

      case Interested =>
        this.interested = true
        session ! Tuple2(endpoint, Interested)

      case Uninterested =>
        this.interested = false
        session ! Tuple2(endpoint, Uninterested)

      case msg =>
        logger.trace(s"Pwp message => $msg")
        // should we let torrent client handle peer timeout ?
        session ! Tuple2(endpoint, msg)
    }
  }

  def attachTcpConn(tcpConn: ActorRef) = {
    tcpConn ! Register(self)
    tcpConn ! Write(ByteString(handshake.encode))
  }

  @tailrec
  private def decodeMessage(data: ByteString, messages: List[Message] = Nil): List[Message] =
    decoder.decode(data) match {
      case (Some(msg), remaining) => decodeMessage(remaining, msg :: messages)
      case (None, _)              => messages.reverse
    }

}
