package storrent.cmd

import java.nio.file.{ Paths, Files }

import akka.actor.ActorSystem
import storrent.Torrent
import storrent.client.TorrentSession
import storrent.pwp.PwpPeer

import scala.util.{ Failure, Success }

/**
 * User: zhaoyao
 * Date: 3/25/15
 * Time: 16:50
 */
object Download {

  def start(torrent: Torrent): Unit = {
    val system = ActorSystem("storrent")
    val session = system.actorOf(TorrentSession.props(torrent, "file:///Users/zhaoyao/storrent_download22"))

  }

  def main(args: Array[String]): Unit = {
    Torrent(Files.readAllBytes(Paths.get("/Users/zhaoyao/Workspaces/Gump/godzilla/webapp/r2.torrent"))) match {
      case Success(torrent) =>
        start(torrent)

      case Failure(e) =>
        e.printStackTrace()
        System.exit(1)

    }
  }

}
