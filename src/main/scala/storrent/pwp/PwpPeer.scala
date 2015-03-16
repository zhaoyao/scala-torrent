package storrent.pwp

import akka.actor._
import storrent.{Peer, Torrent}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

object PwpPeer {

  case object Announce

  case class AddPeers(peers: List[Peer])

}

class PwpPeer(torrent: Torrent) extends Actor with ActorLogging {
  import storrent.pwp.PwpPeer._
  import context.dispatcher

  val peerConns = new mutable.HashMap[String, ActorRef]()

  def announce(): (Int, List[Peer]) = {
    (-1, Nil)
  }

  override def preStart(): Unit = {
    //TODO start peer tcp listening
     context.system.scheduler.scheduleOnce(0.seconds, self, Announce)
  }

  override def receive: Receive = {
    case Announce =>
      val (announceInterval, peers) = announce()
      context.system.scheduler.scheduleOnce(announceInterval.seconds, self, Announce)
      self ! AddPeers(peers)

    case AddPeers(peers) =>
        peers.foreach { p =>
          val c = peerConns.getOrElseUpdate(p.id, createPeer(p))
          //
        }

    case Terminated(c) =>
      peerConns.retain((_, p) => p != c)

  }

  def createPeer(p: Peer): ActorRef = {
    val c = context.actorOf(PeerConnection.props(p), s"${torrent.info.hash}/${p.id}-conn")
    context.watch(c)
    c
  }
}
