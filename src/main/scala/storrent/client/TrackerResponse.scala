package storrent.client

import sbencoding.{ BencodingFormat, DefaultBencodingProtocol }
import storrent.Peer

trait TrackerResponse

object TrackerResponse {

  case class Success(interval: Int,
                     peers: List[Peer],
                     complete: Option[Int] = None,
                     incomplete: Option[Int] = None) extends TrackerResponse

  case class Error(reason: String) extends TrackerResponse

  implicit object BencodingProtocol extends DefaultBencodingProtocol {

    implicit def successFormat(compact: Boolean = true): BencodingFormat[Success] = {
      implicit val peerFormat = Peer.BencodingProtocol.peerFormat(compact)
      bencodingFormat(Success, "min interval", "peers", "complete", "incomplete")
    }

    implicit def errorFormat: BencodingFormat[Error] = bencodingFormat(Error, "failure reason")

  }

}
