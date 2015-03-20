package storrent.tracker

import akka.actor.{ ActorSelection, ActorSystem }
import akka.pattern._
import akka.util.Timeout
import spray.http.MediaType
import spray.routing.Directives
import storrent.client.TrackerResponse
import storrent.tracker.TorrentStateActor.PeerUpdate
import storrent.{ Peer, Util }

import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

/**
 * User: zhaoyao
 * Date: 3/5/15
 * Time: 17:54
 */
trait TrackerRoute {
  _: Directives =>

  //  import spray.routing.directives.OnSuccessFutureMagnet._

  val system: ActorSystem
  val torrentManger: ActorSelection

  import sbencoding._
  import system.dispatcher

  implicit val timeout = Timeout(5.seconds)

  def trackerApi = {

    respondWithMediaType(MediaType.custom("application/x-bittorrent")) {

      /*  path("announce") {
          parameterMap { p =>
            println(p)
            complete("")
          }
        } ~*/ path("announce") {
        parameters(
          'info_hash.as[String],
          'peer_id.as[String],
          'ip.as[Option[String]],
          'port.as[Option[Int]],
          'uploaded.as[Long],
          'downloaded.as[Long],
          'left.as[Long],
          'event.as[Option[String]],
          'compact.as[Option[Int]]
        ) { (infoHash, peerId, ip, port, uploaded, downloaded, left, event, compact) =>
            //TODO guess ip address
            //          val (pid, peerAddr) = if (port.get == 7777) {
            //            ("abcdefg", ("127.0.0.1", 7777))
            //          } else {
            //            (peerId, ip.flatMap(ip => port.map(port => (ip, port))))
            //          }

            val infoHashHex = Util.encodeHex(infoHash.getBytes)
            val update = PeerUpdate(infoHashHex, Peer(peerId, "", port.get), event, uploaded, downloaded, left)

            val compactPeer = !compact.isDefined || compact.get == 0

            val result = (torrentManger ? update).mapTo[List[Peer]]

            implicit val successFormat = TrackerResponse.BencodingProtocol.successFormat(compactPeer)
            implicit val errorFormat = TrackerResponse.BencodingProtocol.errorFormat

            //TODO spray-bencoding-support
            onComplete(result) {
              case Success(peers) =>
                complete(TrackerResponse.Success(3600, peers, None, None).toBencoding.toString)
              case Failure(e) =>
                complete(TrackerResponse.Error(e.getMessage).toBencoding.toString)
            }
          }
      }
    }
  }

}
