package storrent.client

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.{ HttpRequest, HttpResponse, Uri }
import storrent.Peer

import scala.concurrent.Future

/**
 * Multi Tracker Extension: http://www.bittorrent.org/beps/bep_0012.html
 */
sealed trait MultitrackerStrategy {

  implicit val system: ActorSystem

  import system.dispatcher

  def announce(infoHash: String,
               peerId: String,
               port: Int,
               uploaded: Int,
               downloaded: Int,
               left: Int,
               event: String = ""): Future[TrackerResponse]

  def doAnnounce(tracker: Uri,
                 infoHash: String,
                 peerId: String,
                 port: Int,
                 uploaded: Int,
                 downloaded: Int,
                 left: Int,
                 event: String): Future[TrackerResponse] = {
    tracker match {
      case u @ (Uri("http", _, _, _, _) | Uri("https", _, _, _, _)) =>
        httpAnnounce(u, infoHash, peerId, port, uploaded, downloaded, left, event)

      case u @ Uri("udp", _, _, _, _) =>
        //todo udp tracker announcement
        Future.failed(new NotImplementedError())
    }
  }

  def httpAnnounce(tracker: Uri,
                   infoHash: String,
                   peerId: String,
                   port: Int,
                   uploaded: Int,
                   downloaded: Int,
                   left: Int,
                   event: String = ""): Future[TrackerResponse] = {
    val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

    val uri = tracker.withQuery(
      ("info_hash", infoHash),
      ("peer_id", peerId),
      ("port", port.toString),
      ("uploaded", uploaded.toString),
      ("downloaded", downloaded.toString),
      ("left", left.toString),
      ("event", event),
      ("compact", "1")
    )

    pipeline(Get(uri)).map(handleTrackerResponse)
  }

  def handleTrackerResponse(response: HttpResponse): TrackerResponse = {
    import sbencoding._
    import storrent.client.TrackerResponse.BencodingProtocol._

    implicit def successFormat = TrackerResponse.BencodingProtocol.successFormat(true)

    val successOrError = response.entity.data.toByteArray.parseBencoding
      .convertTo[Either[TrackerResponse.Success, TrackerResponse.Error]]

    successOrError.fold(identity, identity)
  }

}

class SingleTracker(val system: ActorSystem, onlyTracker: String) extends MultitrackerStrategy {

  override def announce(infoHash: String,
                        peerId: String,
                        port: Int,
                        uploaded: Int,
                        downloaded: Int,
                        left: Int,
                        event: String): Future[TrackerResponse] = {
    doAnnounce(Uri(onlyTracker), infoHash, peerId, port, uploaded, downloaded, left, event)
  }
}

// d['announce-list'] = [ [tracker1], [backup1], [backup2] ]
class TryAll(val system: ActorSystem, trackers: List[String]) extends MultitrackerStrategy {

  import system.dispatcher

  override def announce(infoHash: String,
                        peerId: String,
                        port: Int,
                        uploaded: Int,
                        downloaded: Int,
                        left: Int,
                        event: String): Future[TrackerResponse] = {

    Future.traverse(trackers) { tracker =>
      doAnnounce(Uri(tracker), infoHash, peerId, port, uploaded, downloaded, left, event)
    }.map { responses =>

      if (responses.forall(_.isInstanceOf[TrackerResponse.Error])) {
        responses.find(_.isInstanceOf[TrackerResponse.Error]).get

      } else {
        val interval = responses.map {
          case TrackerResponse.Success(interval, _, _, _) => interval
          case _ => 0
        }.max

        val peers = responses.flatMap {
          case TrackerResponse.Success(_, peers, _, _) => peers
          case _                                       => Nil
        }

        val complete = responses.map {
          case TrackerResponse.Success(_, _, Some(c), _) => c
          case _                                         => 0
        }.sum

        val incomplete = responses.map {
          case TrackerResponse.Success(_, _, _, Some(c)) => c
          case _                                         => 0
        }.sum

        TrackerResponse.Success(interval, peers.toList, Some(complete), Some(incomplete))
      }
    }
  }
}

// d['announce-list'] = [[ tracker1, tracker2, tracker3 ]]
class UseBest(val system: ActorSystem, trackers: List[String]) extends MultitrackerStrategy {

  import system.dispatcher

  var current = trackers

  override def announce(infoHash: String,
                        peerId: String,
                        port: Int,
                        uploaded: Int,
                        downloaded: Int,
                        left: Int,
                        event: String): Future[TrackerResponse] = {
    // 尝试每一个tracker，成功后调整下次announce的顺序(成功的tracker放到队列的前端，下次优先使用)

    // 1. block announce，尝试到成功为止，更新当前队列
    // 2. future.recoverWith(announceNextTracker).onSuccess( currentTracker :: (current.remove(currentTracker))

    def tryAnnounce(trackers: List[String],
                    infoHash: String,
                    peerId: String,
                    port: Int,
                    uploaded: Int,
                    downloaded: Int,
                    left: Int,
                    event: String): Future[(TrackerResponse, String)] = {
      doAnnounce(Uri(trackers.head), infoHash, peerId, port, uploaded, downloaded, left, event)
        .map(r => (r, trackers.head))
        .recoverWith {
          case e =>
            if (current.tail.isEmpty) {
              Future.failed(new RuntimeException("All tracker failed"))
            } else {
              tryAnnounce(current.tail, infoHash, peerId, port, uploaded, downloaded, left, event)
            }
        }
    }

    val ret = tryAnnounce(current, infoHash, peerId, port, uploaded, downloaded, left, event)

    ret.onSuccess {
      case (r, successTracker) =>
        synchronized {
          this.current = successTracker :: (current.filterNot(t => t == successTracker))
        }
    }

    ret.map(_._1)
  }

}

// d['announce-list'] = [ [ tracker1, tracker2 ], [backup1] ]
class PreferFirstTier(val system: ActorSystem, firstTiers: List[String], backups: List[List[String]]) extends MultitrackerStrategy {
  override def announce(infoHash: String, peerId: String, port: Int, uploaded: Int, downloaded: Int, left: Int, event: String): Future[TrackerResponse] = ???
}

