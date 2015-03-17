package storrent.tracker

import akka.actor.{ Actor, ActorLogging }
import storrent.Peer
import storrent.tracker.TorrentStateActor.PeerUpdate

object TorrentStateActor {

  case class PeerUpdate(infoHash: String,
                        peer: Peer,
                        event: Option[String],
                        uploaded: Long,
                        downloaded: Long,
                        left: Long)

}

class TorrentStateActor(infoHash: String) extends Actor with ActorLogging {

  var peers: Map[String, PeerState] = Map.empty

  def updatePeer(event: Option[String], update: PeerUpdate): Unit = {
    val firstSeen = peers.get(update.peer.id)
      .map(_.firstSeen)
      .getOrElse(System.currentTimeMillis())

    peers = peers + (update.peer.id -> PeerState(
      update.peer, update.uploaded, update.downloaded,
      update.left, firstSeen, System.currentTimeMillis(), event.getOrElse("started")
    ))

    log.info("Torrent[{}] peers => {}", infoHash, peers)
  }

  def selectPeers(): List[Peer] = {
    this.peers.values
      .filter(s => s.state == "started" || s.state == "completed")
      .take(20).map(_.peer).toList
  }

  override def receive: Receive = {
    case u @ PeerUpdate(ih, peer, e, _, _, _) => e match {
      case Some("stopped") =>
        peers -= peer.id
        sender() ! Nil
      case _ =>
        sender() ! selectPeers()
        updatePeer(e, u)
    }

  }

}
