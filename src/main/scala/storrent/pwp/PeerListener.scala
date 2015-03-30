package storrent.pwp

import akka.actor.Actor
import storrent.Peer

object PeerListener {

  case class PeerAdded(peer: Peer)
  case class PeerRemoved(peer: Peer)

}

/**
  * An actor who accepts `OnPeerAdded` and `OnPeerRemoved` messages, and call the `onPeerAdded` `onPeerRemoved` methods.
  * see TorrentSession. This must should work with PwpPeer, who sends the `OnPeerAdded` and `OnPeerRemoved` messages.
  */
trait PeerListener extends Actor {

  import PeerListener._

  override def receive: Receive = super.receive.orElse({
    case PeerAdded(p) => onPeerAdded(p)
    case PeerRemoved(p) => onPeerRemoved(p)
  })

  def onPeerAdded(peer: Peer)

  def onPeerRemoved(peer: Peer)

}