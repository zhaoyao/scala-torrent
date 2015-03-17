package storrent.client

import akka.actor.{ Props, Actor, ActorLogging, ActorRef }
import akka.pattern._
import storrent.Torrent

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

  val multitrackerStrategy = (torrent.announce, torrent.announceList) match {
    case (t, Nil) =>
      new SingleTracker(context.system, t)
    case (t, trackers) if trackers.forall(_.size == 1) =>
      new TryAll(context.system, trackers.map(_(0)))
    case (t, trackers :: Nil) =>
      new UseBest(context.system, trackers)
    case (t, trackers) if trackers.size > 1 && trackers.forall(_.size >= 1) =>
      new PreferFirstTier(context.system, trackers.head, trackers.tail)
  }

  override def receive: Receive = {
    case Announce(uploaded, downloaded, left, event) =>
      val response: Future[TrackerResponse] =
        multitrackerStrategy.announce(
          new String(torrent.info.hashRaw, "ISO-8859-1"),
          peerId, port,
          uploaded, downloaded, left, event
        )
      pipe(response).to(sender)

  }

}
