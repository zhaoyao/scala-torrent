package storrent.pwp

import java.nio.file.{ Paths, Files }

import akka.actor.{ Props, ActorSystem }
import shapeless.Succ
import storrent.pwp.PeerListener.{ PeerRemoved, PeerAdded }
import storrent.{ Torrent, ActorStack, Peer }

import scala.collection.mutable
import scala.util.Success

object PeerListener {

  case class PeerAdded(peer: Peer)
  case class PeerRemoved(peer: Peer)

}

object Test1 {
  class Test extends PeerListener {
    override def onPeerAdded(peer: Peer): Unit = println("Peer added: " + peer)

    override def onPeerRemoved(peer: Peer): Unit = println("Peer removed: " + peer)

    override def wrappedReceive: Receive = {
      case x => println("Got " + x)
    }
  }

  def main(args: Array[String]): Unit = {
    Torrent(Files.readAllBytes(Paths.get("/tmp/r.torrent"))) match {
      case Success(t) =>
        println(t)
    }
  }
}

/**
 * An actor who accepts `OnPeerAdded` and `OnPeerRemoved` messages, and call the `onPeerAdded` `onPeerRemoved` methods.
 * see TorrentSession. This must should work with PwpPeer, who sends the `OnPeerAdded` and `OnPeerRemoved` messages.
 */
trait PeerListener extends ActorStack {

  import PeerListener._

  override def receive: Receive = {
    case PeerAdded(p)   => onPeerAdded(p)
    case PeerRemoved(p) => onPeerRemoved(p)
    case x              => super.receive(x)
  }

  def onPeerAdded(peer: Peer)

  def onPeerRemoved(peer: Peer)

}