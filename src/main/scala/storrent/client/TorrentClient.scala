package storrent.client

import akka.actor.{ Actor, ActorLogging }
import storrent.Torrent

/**
 * User: zhaoyao
 * Date: 3/12/15
 * Time: 11:59
 */
class TorrentClient(metainfo: Torrent) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("Starting TorrentClient[{}]", metainfo.infoHash)
  }

  override def receive: Receive = {
    case _ =>
  }
}
