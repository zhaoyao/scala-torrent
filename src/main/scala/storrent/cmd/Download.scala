package storrent.cmd

import java.nio.file.{ Paths, Files }

import akka.actor.ActorSystem
import storrent.Torrent
import storrent.client.TorrentSession
import storrent.pwp.PeerManager$

import scala.util.{ Failure, Success }

/**
 * User: zhaoyao
 * Date: 3/25/15
 * Time: 16:50
 */
object Download {

  def start(torrent: Torrent, storeDir: String): Unit = {
    val system = ActorSystem("storrent")
    system.actorOf(TorrentSession.props(torrent, s"file://$storeDir"))
  }

  def main(args: Array[String]): Unit = {
    Torrent(Files.readAllBytes(Paths.get(args(0)))) match {
      case Success(torrent) =>
        start(torrent, args(1))

      case Failure(e) =>
        e.printStackTrace()
        System.exit(1)

    }
  }

}
