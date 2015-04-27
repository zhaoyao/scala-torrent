package storrent.dht

import java.net.InetSocketAddress

import akka.actor.ActorRef
import storrent.{Peer, Slf4jLogging, ActorStack}

import scala.collection.mutable

object DHTManager {

  val DefaultBootstrapNodes = List(
    ("router.bittorrent.com", 8991)
  ).map(p => new InetSocketAddress(p._1, p._2))

  case class AddTorrent(infoHash: String, nodes: List[Peer])
}

class DHTManager(bootstrapNodes: List[InetSocketAddress] = DHTManager.DefaultBootstrapNodes) extends ActorStack with Slf4jLogging {

  import DHTManager._

  val nodes = mutable.Map[String, ActorRef]()

  override def wrappedReceive: Receive = {
    case AddTorrent(infoHash, nodes) =>
  }

}
