package storrent.cmd

import akka.actor.{ Props, ActorSystem }
import spray.http.Uri
import storrent.announce.UdpAnnouncer

/**
 * User: zhaoyao
 * Date: 4/20/15
 * Time: 17:46
 */
object Test extends App {

  implicit val system = ActorSystem()

  val a = system.actorOf(Props(classOf[UdpAnnouncer], Uri(args(0))))

  system.awaitTermination()
}
