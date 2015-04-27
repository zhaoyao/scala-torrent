package storrent.dht

import sbencoding._
import storrent.Util

/**
 * User: zhaoyao
 * Date: 4/27/15
 * Time: 17:41
 */
case class Node(id: BigInt, ip: String, port: Int)

object NodeBencodingProtocol extends DefaultBencodingProtocol {

  implicit val nodeFormat = new BencodingFormat[Node] {

    override def write(obj: Node): BcString = BcString(obj.id.toByteArray ++ Util.compactIpAndPort(obj.ip, obj.port))

    override def read(value: BcValue): Node = value match {
      case BcString(data) =>
        val (ip, port) = Util.parseCompactIpAndPort(data.drop(20))
        Node(BigInt(data.dropRight(6)), ip, port)
      case x              => deserializationError("Invalid dht node, got " + x.getClass.getSimpleName)
    }
  }

}