package storrent.pwp

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Actor}
import akka.io.{IO, Tcp}
import akka.io.Tcp._
import storrent.Peer

object PeerConnection {

  def props(peer: Peer) = Props(classOf[PeerConnection], peer)

}


class PeerConnection(peer: Peer) extends Actor {

  override def preStart(): Unit = {
    IO(Tcp)(context.system) ! Connect(new InetSocketAddress(peer.ip, peer.port))
  }

  override def receive: Receive = connecting

  def connecting: Receive = {
    case c @ Connected(remote, local) =>
      val conn = sender()
      conn ! Register(self)

//      conn ! Write(Handshake().byteString)
      context become connected(conn)

    case CommandFailed(_: Connect) =>
      //TODO retry
      context stop self

  }

  def connected(conn: ActorRef): Receive = {


    case CommandFailed(_: Write) =>
      // log handshake failed
      context stop self
  }
}
