package storrent.pwp

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import storrent.pwp.Message._
import storrent.{Peer, PeerId}

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object PeerConnection {

  def props(infoHash: String, selfPeerId: String, target: Peer) =
    Props(classOf[PeerConnection], infoHash, selfPeerId, target)

}

class PeerConnection(infoHash: String,
                     selfPeerId: String,
                     targetPeer: Peer) extends Actor with ActorLogging with Stash {

  import context.dispatcher

  val decoder = new MessageDecoder()
  val handshake: Handshake = Handshake(infoHash, selfPeerId)

  val torrentClient = context.parent

  val choked = new AtomicBoolean(true)

  val interested = new AtomicBoolean(false)

  override def preStart(): Unit = {
    IO(Tcp)(context.system) ! Connect(new InetSocketAddress(targetPeer.ip, targetPeer.port))
  }

  override def receive: Receive = connecting

  def connecting: Receive = {
    case c@Connected(remote, local) =>
      val conn = sender()
      conn ! Register(self)

      conn ! Write(ByteString(handshake.encode))
      //TODO detect handshake timeout


    case Received(data) =>
      Try(Handshake.parse(data)) match {
        case Success((hs: Handshake, remaining: ByteString)) =>
          if (hs.peerId != targetPeer.id || hs.protocol != handshake.protocol) {
            log.info("Invalid handshake: {}. peer={} ih={}", hs, targetPeer, infoHash)
            context stop self

          } else {
            handleInboundData(remaining)
            context become connected(sender())
            unstashAll()
            context.system.scheduler.schedule(5.seconds, 5.seconds, self, Keepalive)
          }

        case Failure(e) =>
          log.info("Handshake parsing failed: {}", e.getMessage)
          context stop self
      }

    case CommandFailed(_: Connect) =>
      //TODO retry
      context stop self

    case PeerClosed =>
      log.info("Peer connection closed")
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
      log.info("Peer connection closed")
      context stop self
  }

  def handleInboundData(data: ByteString): Unit = {
    log.info("Got peer data:{}", data.length)
    decodeMessage(data).foreach {
      case Choke =>
        this.choked.set(true)
        torrentClient !(targetPeer, Choke)

      case Unchoke =>
        this.choked.set(false)
        torrentClient !(targetPeer, Unchoke)

      case Interested =>
        this.interested.set(true)
        torrentClient !(targetPeer, Interested)

      case Uninterested =>
        this.interested.set(false)
        torrentClient !(targetPeer, Uninterested)

      case msg =>
        log.info("Pwp message => {}", msg)
          // should we let torrent client handle peer timeout ?
        torrentClient !(targetPeer, msg)
    }
  }

  @tailrec
  private def decodeMessage(data: ByteString, messages: List[Message] = Nil): List[Message] =
    decoder.decode(data) match {
      case (Some(msg), remaining) => decodeMessage(remaining, msg :: messages)
      case (None, _) => messages.reverse
    }

}
