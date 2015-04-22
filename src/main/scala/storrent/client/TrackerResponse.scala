package storrent.client

import sbencoding._
import storrent.Peer
import storrent.Peer.BencodingProtocol.peerFormat

trait TrackerResponse

object TrackerResponse {

  case class Success(interval: Int,
                     peers: List[Peer],
                     complete: Option[Int] = None,
                     incomplete: Option[Int] = None) extends TrackerResponse

  case class Error(reason: String) extends TrackerResponse

  implicit object BencodingProtocol extends DefaultBencodingProtocol {

    implicit def successFormat(compact: Boolean = true): BencodingFormat[Success] = new BencodingFormat[Success] {

      override def write(obj: Success): BcValue = {
        if (compact) {
          BcDict(
            "interval" -> BcInt(obj.interval),
            "peers" -> BcString(Array.concat(obj.peers.map(_.compact): _*)),
            "complete" -> obj.complete.map(_.toBencoding).getOrElse(BcNil),
            "incomplete" -> obj.incomplete.map(_.toBencoding).getOrElse(BcNil)
          )
        } else {
          BcDict(
            "interval" -> BcInt(obj.interval),
            "peers" -> BcList(obj.peers.map(peerFormat.write): _*),
            "complete" -> obj.complete.map(_.toBencoding).getOrElse(BcNil),
            "incomplete" -> obj.incomplete.map(_.toBencoding).getOrElse(BcNil)
          )
        }
      }

      override def read(value: BcValue): Success = {
        val peers = value.asBcDict.getFields("peers") match {
          case Seq(BcString(peersData)) =>
            peersData.sliding(6, 6).map(Peer.parseCompact).toList
          case Seq(peers: BcList) =>
            peers.elements.map(peerFormat.read).toList
        }

        value.asBcDict.getFields("interval", "min interval", "complete", "incomplete") match {
          case Seq(BcInt(interval), BcInt(minInterval), BcString(peersData), c, i) =>
            Success(minInterval.toInt,
              peers,
              c.convertTo[Option[Int]], i.convertTo[Option[Int]])
          case Seq(BcInt(interval), BcNil, BcString(peersData), c, i) =>
            Success(interval.toInt,
              peers,
              c.convertTo[Option[Int]], i.convertTo[Option[Int]])
        }
      }
    }

    implicit def errorFormat: BencodingFormat[Error] = bencodingFormat(Error, "failure reason")

  }

}
