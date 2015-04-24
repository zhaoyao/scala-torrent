package storrent.pwp

import akka.actor.{ ActorRef, Props }
import storrent.client._
import storrent.pwp.PeerManager.{ AddPeer, StartAnnounce }
import storrent.{ ActorStack, Slf4jLogging, Torrent, Util }

import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object Announcer {

  case class Announce(uploaded: Long, downloaded: Long, left: Long, event: String)
  private case object DoAnnounce

  def props(peerId: String,
            port: Int,
            torrent: Torrent,
            receiver: ActorRef) = Props(classOf[Announcer], peerId, port, torrent, receiver)
}

class Announcer(peerId: String,
                torrent: Torrent,
                peerManager: ActorRef) extends ActorStack with Slf4jLogging {

  import Announcer._
  import context.dispatcher

  var port: Int = _

  var downloaded: Long = 0
  var uploaded: Long = 0
  var left: Long = torrent.metainfo.info.length

  val multitrackerStrategy = (torrent.metainfo.announce, torrent.metainfo.announceList) match {
    case (Some(t), None | Some(Nil)) =>
      new SingleTracker(context.system, t)
    case (_, Some(trackers)) if trackers.forall(_.size == 1) =>
      new TryAll(context.system, trackers.map(_(0)))
    case (_, Some(trackers :: Nil)) =>
      new UseBest(context.system, trackers)
    case (_, Some(trackers)) if trackers.size > 1 && trackers.forall(_.size >= 1) =>
      new PreferFirstTier(context.system, trackers.head, trackers.tail)
  }

  override def wrappedReceive: Receive = {
    case StartAnnounce(p) =>
      this.port = p
      context become announce
      context.system.scheduler.schedule(5.seconds, 5.seconds, self, DoAnnounce)
  }

  def announce: Receive = {
    case DoAnnounce =>
      multitrackerStrategy.announce(
        Util.urlEncodeInfoHash(torrent.infoHash),
        peerId, port,
        uploaded, downloaded, left, ""
      ).onComplete(self ! _)
      context become waitAnnounceResult
  }

  def waitAnnounceResult: Receive = {
    case Success(TrackerResponse.Success(_, peers, _, _)) =>
      peers.foreach(p => peerManager ! AddPeer(p))
      context become announce

    case Success(TrackerResponse.Error(reason)) =>
      logger.warn(s"Announce failed: $reason")
      context become announce

    case Failure(e) =>
      context become announce

  }
}
