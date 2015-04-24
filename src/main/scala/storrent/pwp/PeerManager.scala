package storrent.pwp

import java.net.InetSocketAddress

import akka.actor._
import akka.io.Tcp._
import akka.pattern._
import akka.util.Timeout
import storrent._
import storrent.pwp.PeerConnection.Start
import storrent.pwp.PeerListener.{ PeerAdded, PeerRemoved }
import storrent.pwp.PeerManager.ConnectionProcessor.ProcessPeerConn

import scala.collection.mutable

object PeerManager {

  private case class UpdatePeerStats(uploaded: Long, downloaded: Long, left: Long)

  private case class PeerUp(peer: Peer, conn: ActorRef, outbound: Boolean)

  private case class PeerDown(peer: Peer)

  case class StartAnnounce(port: Int)

  case object DisableOutboundConnection

  case class AddPeer(peer: Peer, tcpConn: Option[ActorRef] = None)

  case class ClosePeer(peer: Peer)

  def props(torrent: Torrent, port: Int, peerListener: ActorRef, maxConns: Int = 64, outboundConnectionEnabled: Boolean = true) =
    Props(classOf[PeerManager], torrent, port, peerListener, maxConns, outboundConnectionEnabled)

  object ConnectionProcessor {

    case class ProcessPeerConn(peer: Peer, conn: ActorRef, tcpConn: Option[ActorRef])

  }

  private class ConnectionProcessor extends ActorStack with Slf4jLogging {
    import storrent.pwp.PeerManager.ConnectionProcessor.ProcessPeerConn
    import context.dispatcher

    override def wrappedReceive: Receive = {
      case ProcessPeerConn(peer, conn, tcpConn) =>
        val commander = sender()
        (conn ? Start(tcpConn))(Timeout(PeerConnection.ConnectTimeout)).mapTo[Boolean].map({
          case true =>
            commander ! PeerUp(peer, conn, tcpConn.isDefined)
          case false =>
            commander ! PeerDown(peer)
        })
    }
  }

  case class Received(peer: Peer, msg: Message)
  case class Send(peer: Peer, msg: Message)

}

class PeerManager(torrent: Torrent,
                  port: Int,
                  peerListener: ActorRef,
                  maxConn: Int,
                  var outboundConnectionEnabled: Boolean) extends ActorStack with Slf4jLogging {

  import storrent.pwp.PeerManager._

  val session = context.parent

  val peerConns = mutable.Map[Peer, ActorRef]()
  val pendingConns = mutable.Map[Peer, ActorRef]()

  val outboundConnections = mutable.Set[Peer]()

  val id = PeerId()

  var connProcessor: ActorRef = context.actorOf(Props[ConnectionProcessor])

  var announcer: ActorRef = context.actorOf(Props(classOf[Announcer], id, torrent, self))

  var peerAcceptor = context.actorOf(Props(classOf[PeerAcceptor],
    new InetSocketAddress("0.0.0.0", port), self))

  override def wrappedReceive: Receive = ready orElse {
    case PeerAcceptor.Started(b) =>
      announcer ! StartAnnounce(b.getPort)

    case DisableOutboundConnection =>
      outboundConnectionEnabled = false
  }

  def ready: Receive = {
    // suppress warning: a type was inferred to be `Any`; this may indicate a programming error.
    val receive: PartialFunction[Any, Unit] =
      peerTerminated orElse
        peerStateChange orElse
        peerMessage orElse
        managePeer
    receive
  }

  def managePeer: Receive = {
    case AddPeer(peer, tcpConn) =>
      if (peerConns.size >= maxConn) {
        None
      } else {
        if (pendingConns.contains(peer)) {
          for (c <- tcpConn) c ! Close
          None
        } else {
          val conn = newPeerConn(peer, tcpConn.isDefined)
          pendingConns(peer) = conn
          connProcessor ! ProcessPeerConn(peer, conn, tcpConn)
          Some(conn)
        }
      }

    case ClosePeer(p) =>
      self ! PeerDown(p)
  }

  def peerTerminated: Receive = {
    case Terminated(c) =>
      peerConns.retain((peer, conn) => {
        if (conn eq c) {
          // delete from outboundConnections too
          outboundConnections -= peer

          self ! PeerDown(peer)
          false
        } else {
          true
        }
      })
  }

  def peerStateChange: Receive = {
    case PeerUp(p, conn, outbound) =>
      if (outbound)
        outboundConnections += p
      pendingConns -= p
      peerConns += (p -> conn)
      peerListener ! PeerAdded(p)

    case PeerDown(p) =>
      if (peerConns.contains(p)) {
        peerListener ! PeerRemoved(p)
      }
      peerConns -= p
      pendingConns -= p
      outboundConnections -= p
  }

  def peerMessage: Receive = {
    case Send(p: Peer, msg: Message) =>
      //转发消息
//      logger.info(s"Sending peer message $p => $msg")
      peerConns.get(p) match {
        case Some(conn) =>
          conn.forward(msg)
        case None =>
          // sync peer state
          peerListener ! PeerRemoved(p)
          logger.info("Unable to route message[{}] to peer[{}]", msg, p)
      }

    case Received(peer, msg) =>
      session ! ((peer, msg))
  }

  def closePeer(p: Peer) = {
    if (peerConns.contains(p)) {
      logger.info("Close peer connection: {}", p)
      context stop peerConns(p)
    }
    self ! PeerDown(p)
  }

  private def newPeerConn(peer: Peer, inbound: Boolean) = {
    context.actorOf(PeerConnection.props(torrent.infoHash, id, peer, self, inbound))
  }
}
