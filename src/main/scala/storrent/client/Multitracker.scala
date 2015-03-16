package storrent.client

import spray.client.pipelining._
import spray.http.{HttpRequest, HttpResponse, Uri}
import storrent.Peer
import storrent.bencode.BencodeDecoder

import scala.concurrent.{ExecutionContext, Future}

/**
 * Multi Tracker Extension: http://www.bittorrent.org/beps/bep_0012.html
 */
sealed trait MultitrackerStrategy {

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
      case u@(Uri("http", _, _, _, _) | Uri("https", _, _, _, _)) =>
        httpAnnounce(u, infoHash, peerId, port, uploaded, downloaded, left, event)

      case u@Uri("udp", _, _, _, _) =>
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

    pipeline(Get(uri)).flatMap { resp =>
      BencodeDecoder.decode(resp.entity.asString) match {
        case m: Map[_, _] => Future.successful(handleTrackerResponse(m.asInstanceOf[Map[String, Any]]))
        case _ => Future.failed(new RuntimeException("Invalid tracker response"))
      }
    }
  }

  def handleTrackerResponse(response: Map[String, Any]): TrackerResponse = {
    if (response.contains("failure reason")) {
      new RuntimeException("Tracker error: " + response("failure reason"))

    }
    val interval = response.getOrElse("interval", 300l).asInstanceOf[Long].toInt
    val complete = response.get("complete").asInstanceOf[Option[Long]].map(_.toInt)
    val incomplete = response.get("incomplete").asInstanceOf[Option[Long]].map(_.toInt)

    val peers = response("peers").asInstanceOf[List[_]].map {
      case compacted: String =>
        Peer.parseCompact(compacted.getBytes("ISO-8859-1"))
      case fields: Map[String, _] =>
        //TODO 如何实现的更优雅一些，感觉从bencode库入手比较好，去看json4s的代码
        Peer(
          fields.get("id").asInstanceOf[String],
          fields.get("ip").asInstanceOf[String],
          fields.get("port").asInstanceOf[Long].toInt)
    }

    TrackerResponse(interval, peers, complete, incomplete)
  }


}

class SingleTracker(onlyTracker: String) extends MultitrackerStrategy {

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
class TryAll(trackers: List[String])(implicit ec: ExecutionContext) extends MultitrackerStrategy {

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

      val interval = responses.head.interval

      val peers = responses.map(_.peers).foldLeft(Set.empty[Peer])((s, peers) => s ++ peers)

      val complete = responses.filter(_.complete.isDefined) match {
        case Nil => None
        case xs => Some(xs.map(_.complete.get).sum)
      }

      val incomplete = responses.filter(_.incomplete.isDefined) match {
        case Nil => None
        case xs => Some(xs.map(_.incomplete.get).sum)
      }

      TrackerResponse(interval, peers.toList, complete, incomplete)
    }
  }
}

// d['announce-list'] = [[ tracker1, tracker2, tracker3 ]]
class UseBest(trackers: List[String]) extends MultitrackerStrategy {

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
class PreferFirstTier(firstTiers: List[String], backups: List[List[String]]) extends MultitrackerStrategy {
  override def announce(infoHash: String, peerId: String, port: Int, uploaded: Int, downloaded: Int, left: Int, event: String): Future[TrackerResponse] = ???
}

