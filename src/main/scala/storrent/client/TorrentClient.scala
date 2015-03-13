package storrent.client

import akka.actor.{ActorLogging, Actor}
import akka.actor.Actor.Receive
import storrent.Torrent

/**
 * User: zhaoyao
 * Date: 3/12/15
 * Time: 11:59
 */
class TorrentClient(metainfo: Torrent) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("Starting TorrentClient[{}]", metainfo.info.hash)
  }

  override def receive: Receive = {
    case _ =>
  }
}
