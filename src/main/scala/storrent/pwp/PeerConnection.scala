package storrent.pwp

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import storrent.Peer
import storrent.extension.{AdditionalMessageDecoding, HandshakeEnabled}
import storrent.pwp.Message._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object PeerConnection {

  def props(infoHash: String, selfPeerId: String, target: Peer, session: ActorRef) =
    Props(classOf[PeerConnection], infoHash, selfPeerId, target, session, Set.empty)

}

class PeerConnection(infoHash: String,
                     selfPeerId: String,
                     targetPeer: Peer,
                     session: ActorRef,
                     handshakeExtensions: Set[HandshakeEnabled with AdditionalMessageDecoding])
  extends Actor with ActorLogging with Stash {

  import context.dispatcher

  var decoder: MessageDecoder = _
  val handshake: Handshake = Handshake(infoHash, selfPeerId)

  val choked = new AtomicBoolean(true)

  val interested = new AtomicBoolean(false)

  val have = mutable.Set[Int]()

  override def preStart(): Unit = {
    log.info("Creating peer connection for ih: {} to {}", infoHash, targetPeer)
    IO(Tcp)(context.system) ! Connect(new InetSocketAddress(targetPeer.ip, targetPeer.port))
  }

  override def receive: Receive = connecting

  object InterestedAck extends Event

  def connecting: Receive = {
    case c@Connected(remote, local) =>
      log.info("Connection to peer {} established", targetPeer)
      val conn = sender()
      conn ! Register(self)

      conn ! Write(ByteString(handshake.encode))
    //TODO detect handshake timeout

    case Received(data) =>
      Try(Handshake.parse(data)) match {
        case Success((hs: Handshake, remaining: ByteString)) =>
          if ((hs.peerId != targetPeer.id && targetPeer.id != "") || hs.protocol != handshake.protocol) {
            log.info("Invalid handshake: {}. peer={} ih={}", hs, targetPeer, infoHash)
            context stop self

          } else {
            decoder = new MessageDecoder(handshakeExtensions
              .filter(_.isEnabled(hs)).map(_.asInstanceOf[AdditionalMessageDecoding]))

            handleInboundData(remaining)
            sender ! Write(ByteString(Interested.encode), InterestedAck)

          }

        case Failure(e) =>
          log.info("Handshake parsing failed: {}", e.getMessage)
          context stop self
      }

    case InterestedAck =>
      log.info("Got InterestedAck")
      context become connected(sender())
      unstashAll()
      context.system.scheduler.schedule(5.seconds, 60.seconds, self, Keepalive)

    case CommandFailed(_: Connect) =>
      //TODO retry
      log.info("Peer[connecting] Unable to connect to peer {}", targetPeer)
      context stop self

    case PeerClosed =>
      log.info("Peer[connecting] connection closed")
      context stop self

    case _ => stash

  }

  def connected(conn: ActorRef): Receive = {
    case m: Message =>
      /* forward message to target peer */
      log.info("Forward pwp message => {}", m)
      //TODO conditional write based on choked state
      conn ! Write(ByteString(m.encode))

    case Received(data) =>
      handleInboundData(data)

    case CommandFailed(_: Write) =>
      // log handshake failed
      context stop self

    case PeerClosed =>
      log.info("Peer[connected] connection closed")
      context stop self
  }

  def handleInboundData(data: ByteString): Unit = {
    decodeMessage(data).foreach {
      case Choke =>
        this.choked.set(true)
        session ! Tuple2(targetPeer, Choke)

      case Unchoke =>
        this.choked.set(false)
        session ! Tuple2(targetPeer, Unchoke)

      case Interested =>
        this.interested.set(true)
        session ! Tuple2(targetPeer, Interested)

      case Uninterested =>
        this.interested.set(false)
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
      case (None, _) => messages.reverse
    }

}
