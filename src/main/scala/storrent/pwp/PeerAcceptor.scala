package storrent.pwp

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.io.Tcp.{ Bind, Bound, CommandFailed, Connected }
import akka.io.{ IO, Tcp }
import storrent.pwp.PeerManager.AddPeer
import storrent.{ ActorStack, Peer, Slf4jLogging }

object PeerAcceptor {
  case object Start
  case class Started(addr: InetSocketAddress)
}

private class PeerAcceptor(localAddr: InetSocketAddress,
                           peerManager: ActorRef) extends ActorStack with Slf4jLogging {
  import PeerAcceptor._

  override def preStart(): Unit = {
    IO(Tcp)(context.system) ! Bind(self, localAddr)
  }

  override def wrappedReceive: Receive = init

  def init: Receive = {
    case b @ Bound(_) =>
      peerManager ! Started(b.localAddress)
      context become inboundConnection

    case CommandFailed(_: Bind) =>
      logger.warn("Failed to start PeerAcceptor")
      context stop self
  }

  def inboundConnection: Receive = {
    case Connected(remote, _) =>
      val conn = sender()
      val peer = Peer("", remote.getAddress.getHostAddress, remote.getPort)
      peerManager ! AddPeer(peer, Some(conn))
  }
}