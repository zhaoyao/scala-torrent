package storrent.pwp

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.io.Tcp._
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import storrent.{ Slf4jLogging, ActorStack, Peer }
import storrent.extension.{ AdditionalMessageDecoding, HandshakeEnabled }
import storrent.pwp.Message._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

object PeerConnection {

  def props(infoHash: String, selfPeerId: String, target: Peer, session: ActorRef) =
    Props(classOf[PeerConnection], infoHash, selfPeerId, target, session, Set.empty)

}

class PeerConnection(infoHash: String,
                     selfPeerId: String,
                     targetPeer: Peer,
                     session: ActorRef,
                     handshakeExtensions: Set[HandshakeEnabled with AdditionalMessageDecoding])
    extends ActorStack with Slf4jLogging with Stash {

  import context.dispatcher

  var decoder: MessageDecoder = _
  val handshake: Handshake = Handshake(infoHash, selfPeerId)

  var choked = false

  var interested = false

  val have = mutable.Set[Int]()

  override def preStart(): Unit = {
    logger.info("Creating peer connection for ih: {} to {}", Array(infoHash, targetPeer))
    IO(Tcp)(context.system) ! Connect(new InetSocketAddress(targetPeer.ip, targetPeer.port))
  }

  override def wrappedReceive: Receive = connecting

  object InterestedAck extends Event

  def connecting: Receive = {
    case c @ Connected(remote, local) =>
      logger.info("Connection to peer {} established", targetPeer)
      val conn = sender()
      conn ! Register(self)

      conn ! Write(ByteString(handshake.encode))
    //TODO detect handshake timeout

    case Received(data) =>
      Try(Handshake.parse(data)) match {
        case Success((hs: Handshake, remaining: ByteString)) =>
          if ((hs.peerId != targetPeer.id && targetPeer.id != "") || hs.protocol != handshake.protocol) {
            logger.info("Invalid handshake: {}. peer={} ih={}", hs, targetPeer, infoHash)
            context stop self

          } else {
            decoder = new MessageDecoder(handshakeExtensions
              .filter(_.isEnabled(hs)).map(_.asInstanceOf[AdditionalMessageDecoding]))

            handleInboundData(remaining)

            context become connected(sender())
            unstashAll()
            context.system.scheduler.schedule(5.seconds, 60.seconds, self, Keepalive)

          }

        case Failure(e) =>
          logger.info("Handshake parsing failed: {}", e.getMessage)
          context stop self
      }

    case CommandFailed(_: Connect) =>
      //TODO retry
      logger.info("Peer[connecting] Unable to connect to peer {}", targetPeer)
      context stop self

    case PeerClosed =>
      logger.info("Peer[connecting] connection closed")
      context stop self

    case _ => stash

  }

  def connected(conn: ActorRef): Receive = {
    case m: Message =>
      logger.debug("Forwarding pwp msg: {}", m)
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

    case PeerClosed =>
      logger.info("Peer[connected] connection closed")
      context stop self
  }

  def handleInboundData(data: ByteString): Unit = {
    decodeMessage(data).foreach {
      case Choke =>
        this.choked = true
        session ! Tuple2(targetPeer, Choke)

      case Unchoke =>
        this.choked = false
        session ! Tuple2(targetPeer, Unchoke)

      case Interested =>
        this.interested = true
        session ! Tuple2(targetPeer, Interested)

      case Uninterested =>
        this.interested = false
        session ! Tuple2(targetPeer, Uninterested)

      case msg =>
        //        log.info("Pwp message => {}", msg)
        // should we let torrent client handle peer timeout ?
        session ! Tuple2(targetPeer, msg)
    }
  }

  @tailrec
  private def decodeMessage(data: ByteString, messages: List[Message] = Nil): List[Message] =
    decoder.decode(data) match {
      case (Some(msg), remaining) => decodeMessage(remaining, msg :: messages)
      case (None, _)              => messages.reverse
    }

}
