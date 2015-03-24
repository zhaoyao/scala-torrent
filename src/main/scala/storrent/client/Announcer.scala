package storrent.client

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.pattern._
import storrent.{ Util, Torrent }

import scala.concurrent.Future

object Announcer {

  case class Announce(uploaded: Int, downloaded: Int, left: Int, event: String)

  def props(peerId: String,
            port: Int,
            torrent: Torrent,
            receiver: ActorRef) = Props(classOf[Announcer], peerId, port, torrent, receiver)
}

class Announcer(peerId: String,
                port: Int,
                torrent: Torrent,
                receiver: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher
  import storrent.client.Announcer._

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

  override def receive: Receive = {
    case Announce(uploaded, downloaded, left, event) =>
      val response: Future[TrackerResponse] =
        multitrackerStrategy.announce(
          Util.urlEncodeInfoHash(torrent.infoHash),
          peerId, port,
          uploaded, downloaded, left, event
        )
      pipe(response).to(sender)

  }

}
