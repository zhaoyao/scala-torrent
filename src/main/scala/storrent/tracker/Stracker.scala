package storrent.tracker

import akka.actor.{ ActorPath, ActorSelection, ActorSystem }
import akka.io.Inet
import spray.can.server.ServerSettings
import spray.routing.{ Directives, SimpleRoutingApp }

import scala.collection.immutable
import scala.util.Properties

/**
 * User: zhaoyao
 * Date: 3/10/15
 * Time: 15:54
 */
object Stracker {

  def start(interface: String,
            port: Int,
            backlog: Int = 100,
            options: immutable.Traversable[Inet.SocketOption] = Nil,
            settings: Option[ServerSettings] = None,
            system: ActorSystem = ActorSystem("stracker")): Unit = {

    val torrentManager = TorrentManager.start(system)
    val webPort = Properties.envOrElse("PORT", "8080").toInt

    implicit val s = system
    new SimpleRoutingApp {}
      .startServer("0.0.0.0", webPort)(new StrackerRoute(system, torrentManager.path).route)

  }

}

class StrackerRoute(val system: ActorSystem,
                    val torrentManagerPath: ActorPath) extends Directives
    with TrackerRoute {

  override val torrentManger: ActorSelection = system.actorSelection(torrentManagerPath)

  val route = trackerApi
}