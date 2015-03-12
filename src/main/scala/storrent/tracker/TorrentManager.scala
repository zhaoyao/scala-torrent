package storrent.tracker

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import storrent.tracker.TorrentStateActor.PeerUpdate

import scala.concurrent.duration.DurationInt

object TorrentManager {

  val actorName = "TorrentManager"

  def start(system: ActorSystem): ActorRef = {
    system.actorOf(Props[TorrentManager])
  }

}

class TorrentManager extends Actor {

  import context.dispatcher

  var torrentStates = scala.collection.mutable.HashMap[String, ActorRef]()
  implicit val timeout = Timeout(5.seconds)

  override def receive: Receive = {
    case u@PeerUpdate(ih, p, _, _, _, _) =>
      val state = torrentStates.getOrElseUpdate(ih,
        context.actorOf(Props(new TorrentStateActor(ih))))
      pipe(state ? u).to(sender())

  }
}
