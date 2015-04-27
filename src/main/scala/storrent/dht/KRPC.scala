package storrent.dht

import sbencoding._
import storrent.Peer
import NodeBencodingProtocol.nodeFormat


/**
 * User: zhaoyao
 * Date: 4/24/15
 * Time: 11:39
 */
object KRPC {

  sealed trait Message {
    def t: Array[Byte]

    def y: String

    def isQuery = y == "q"

    def isResponse = y == "r"

    def isError = y == "e"
  }

  sealed trait QueryMessage {
    def q: String
  }

  sealed trait ResponseMessage

  object Query {
    val Ping = "ping"
    val FindNode = "find_node"
    val GetPeers = "get_peers"
    val AnnouncePeer = "announce_peer"
  }

  case class Query(t: Array[Byte], q: String, a: QueryMessage) extends Message {
    def y = "q"
  }

  case class Response(t: Array[Byte], r: BcValue) extends Message {
    def y = "r"
  }

  case class Error(t: Array[Byte], e: (Int, String)) extends Message {
    def y = "e"

    def code = e._1

    def message = e._2
  }

  case class Ping(id: BigInt) extends QueryMessage {
    override def q: String = Query.Ping
  }

  case class PingResponse(id: BigInt) extends ResponseMessage

  case class FindNode(id: BigInt, target: BigInt) extends QueryMessage {
    override def q = Query.FindNode
  }

  case class FindNodeResponse(id: BigInt, nodes: List[Node]) extends ResponseMessage

  case class GetPeers(id: BigInt, infoHash: Array[Byte]) extends QueryMessage {
    override def q: String = Query.GetPeers
  }

  case class GetPeersResponse(id: BigInt, token: Option[String],
                              values: Option[List[Peer]], nodes: Option[List[Node]])
      extends ResponseMessage

  case class AnnouncePeer(id: BigInt, infoHash: Array[Byte],
                          port: Int, token: String) extends QueryMessage {
    override def q: String = Query.AnnouncePeer
  }

  case class AnnouncePeerResponse(id: BigInt) extends ResponseMessage

  object KrpcProtocol extends DefaultBencodingProtocol {

    import Peer.BencodingProtocol._

    implicit val pingFormat = bencodingFormat1(Ping)
    implicit val findNodeFormat = bencodingFormat2(FindNode)
    implicit val getPeersFormat = bencodingFormat2(GetPeers)
    implicit val announcePeerFormat = bencodingFormat4(AnnouncePeer)

    implicit val queryMessageFormat = lift(new BencodingWriter[QueryMessage] {
      override def write(q: QueryMessage): BcValue = q match {
        case q: Ping         => pingFormat.write(q)
        case q: FindNode     => findNodeFormat.write(q)
        case q: GetPeers     => getPeersFormat.write(q)
        case q: AnnouncePeer => announcePeerFormat.write(q)
      }
    })

    implicit val pingRespFormat = bencodingFormat1(PingResponse)
    implicit val findNodeRespFormat = new BencodingFormat[FindNodeResponse] {
      override def write(obj: FindNodeResponse): BcValue = {
        BcDict(
          "id" -> obj.id.toBencoding,
          "nodes" -> BcString(Array.concat(obj.nodes.map(n => nodeFormat.write(n).value): _*))
        )
      }

      override def read(value: BcValue): FindNodeResponse = value.asBcDict.getFields("id", "nodes") match {
        case Seq(BcString(id), nodes: BcString) =>
          FindNodeResponse(BigInt(id), nodes.sliding(26).map(nodeFormat.read).toList)
        case _ => deserializationError("Malformed FindNodeResponse response")
      }
    }

    implicit val getPeersRespFormat = new BencodingFormat[GetPeersResponse] {

      override def write(obj: GetPeersResponse): BcValue = obj match {
        case GetPeersResponse(id, token, Some(values), None) =>
          BcDict(
            "id" -> id.toBencoding,
            "token" -> token.toBencoding,
            "values" -> values.map(_.compact).toBencoding
          )
        case GetPeersResponse(id, token, None, Some(nodes)) =>
          BcDict(
            "id" -> id.toBencoding,
            "token" -> token.toBencoding,
            "nodes" -> BcString(Array.concat(nodes.map(n => nodeFormat.write(n).value): _*))
          )
      }

      override def read(value: BcValue): GetPeersResponse = value.asBcDict.getFields("id", "token", "values", "nodes") match {
        case Seq(BcString(id), token, values: BcList, BcNil) =>
          import Peer.BencodingProtocol.compactPeerFormat
          GetPeersResponse(BigInt(id), token.convertTo[Option[String]],
            Some(values.map(compactPeerFormat.read).toList), None)
        case Seq(BcString(id), token, BcNil, nodes: BcString) =>
          GetPeersResponse(BigInt(id), token.convertTo[Option[String]],
            None, Some(nodes.sliding(26).map(nodeFormat.read).toList))

      }
    }

    implicit val announcePeerRespFormat = bencodingFormat1(AnnouncePeerResponse)

    implicit val responseMessageFormat = lift(new BencodingWriter[ResponseMessage] {
      override def write(r: ResponseMessage): BcValue = r match {
        case q: PingResponse         => pingRespFormat.write(q)
        case q: FindNodeResponse     => findNodeRespFormat.write(q)
        case q: GetPeersResponse     => getPeersRespFormat.write(q)
        case q: AnnouncePeerResponse => announcePeerRespFormat.write(q)
      }
    })

    implicit val queryFormat = new BencodingFormat[Query] {
      override def write(obj: Query): BcValue = BcDict(
        "t" -> obj.t.toBencoding,
        "y" -> "q".toBencoding,
        "q" -> obj.a.q.toBencoding,
        "a" -> obj.a.toBencoding
      )

      override def read(value: BcValue): Query = value.asBcDict.getFields("t", "q", "a") match {
        case Seq(BcString(t), BcString(Array('p', 'i', 'n', 'g')), a: BcDict) =>
          Query(t, "ping", a.convertTo[Ping])
        case Seq(BcString(t), BcString(Array('f', 'i', 'n', 'd', '_', 'n', 'o', 'd', 'e')), a: BcDict) =>
          Query(t, "find_node", a.convertTo[FindNode])
        case Seq(BcString(t), BcString(Array('g', 'e', 't', '_', 'p', 'e', 'e', 'r', 's')), a: BcDict) =>
          Query(t, "get_peers", a.convertTo[GetPeers])
        case Seq(BcString(t), BcString(Array('a', 'n', 'n', 'o', 'u', 'n', 'c', 'e', '_', 'p', 'e', 'e', 'r')), a: BcDict) =>
          Query(t, "announce_peer", a.convertTo[AnnouncePeer])
        case _ => deserializationError("Invalid KRPC query")
      }
    }

    implicit val responseFormat = bencodingFormat2(Response.apply)
    implicit val errorFormat = bencodingFormat2(Error.apply)

    implicit val krpcMessageFormat = new BencodingFormat[Message] {
      override def write(obj: Message): BcValue = obj match {
        case q: Query    => queryFormat.write(q)
        case r: Response => responseFormat.write(r)
        case e: Error    => errorFormat.write(e)
      }

      override def read(value: BcValue): Message = {
        println(value.asBcDict.fields("r").asBcDict.fields.keys)
        value.asBcDict.getFields("y") match {
          case Seq(BcString(Array('q'))) => queryFormat.read(value)
          case Seq(BcString(Array('r'))) => responseFormat.read(value)
          case Seq(BcString(Array('e'))) => errorFormat.read(value)
        }
      }
    }

  }

}
