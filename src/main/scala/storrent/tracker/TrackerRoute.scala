package storrent.tracker

import akka.actor.{ActorSelection, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import spray.http.MediaType
import spray.routing.Directives
import storrent.{Util, Peer}
import TorrentStateActor.PeerUpdate
import storrent.bencode.BencodeEncoder

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

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

          val (pid, peerAddr) = if (port.get == 7777) {
            ("abcdefg", Some(("127.0.0.1", 7777)))
          } else {
            (peerId, ip.flatMap(ip => port.map(port => (ip, port))))
          }

          val infoHashHex = Util.encodeHex(infoHash.getBytes)
          val update = PeerUpdate(infoHashHex, Peer(pid, peerAddr), event, uploaded, downloaded, left)
          val result = (torrentManger ? update).mapTo[List[Peer]].map { peers =>
            val hasAddrs = peers.filter(_.ipPort.isDefined)

            println(hasAddrs)

            if (!compact.isDefined || compact.get == 0) {
              hasAddrs.map(peer => Map(
                "peer_id" -> peer.id,
                "ip" -> peer.ipPort.get._1,
                "port" -> peer.ipPort.get._2
              ))
            } else {
              val r = Array.concat(hasAddrs.map(_.compact.get).toSeq: _*)
              new String(r, "ascii")
            }
          }

          onComplete(result) {
            case Success(peers) =>
              val resp = BencodeEncoder.encode(Map(
                "interval" -> 5 * 60,
                "peers" -> peers
              ))

              complete(resp)
            case Failure(e) =>
              complete(BencodeEncoder.encode(Map(
                "failure" -> e.getMessage
              )))
          }
        }

      }
    }
  }

}
