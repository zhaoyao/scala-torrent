package storrent.dht

import storrent.Peer

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

object RoutingTable {

  val K = 8

  case class NodeState(node: Node,
                       lastActive: Long,
                       latency: Long)

  class Bucket(val range: (BigInt, BigInt)) {

    def keyspaceContains(id: BigInt) = range._1 <= id && id < range._2

    var lastChange = System.currentTimeMillis()

    val peers = new Array[NodeState](K)

    def size = peers.count(_ != null)

    def add(node: Node, latency: Long = -1): Either[Bucket, (Bucket, Bucket)] = {
      if (!keyspaceContains(node.id)) {
        return Left(this)
      }
      if (size >= K) {
        val (lower, higher) = split()
        if (lower.keyspaceContains(node.id)) {
          lower.add(node, latency)
        } else {
          higher.add(node, latency)
        }
        Right((lower, higher))
      } else {
        //do add

        peers((node.id % K).toInt) = NodeState(node, System.currentTimeMillis(), latency)
        Left(this)
      }
    }

    def apply(id: BigInt): Option[NodeState] =
      if (keyspaceContains(id)) {
        Some(peers((id % K).toInt))
      } else {
        None
      }

    def needRefresh = (System.currentTimeMillis() - lastChange) >= 15.minutes.toMillis

    def split(): (Bucket, Bucket) = null
  }

}

class RoutingTable {

}
