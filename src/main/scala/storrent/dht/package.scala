package storrent.dht

import storrent.Peer

sealed trait DHTQuery
sealed trait DHTResponse

case object Ping extends DHTQuery
case class Pong(latencyInMs: Long) extends DHTResponse

case class FindNode(id: String, target: String) extends DHTQuery
case class Nodes(nodes: List[Peer]) extends DHTResponse

case class GetPeers(infoHash: String) extends DHTQuery
case class TryNodes(nodes: List[Peer]) extends DHTResponse
case class Peers(values: List[Peer]) extends DHTResponse

case class Announce(port: Int, infoHash: String) extends DHTQuery
case object Announced extends DHTResponse

class DHTException(code: Int, msg: String) extends RuntimeException(msg)
