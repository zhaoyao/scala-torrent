package storrent.cmd

import java.nio.file.{Paths, Files}

import akka.actor.ActorSystem
import storrent.Torrent
import storrent.pwp.PwpPeer

import scala.util.{Failure, Success}

/**
 * User: zhaoyao
 * Date: 3/25/15
 * Time: 16:50
 */
object Download {

  def start(torrent: Torrent): Unit = {
    val system = ActorSystem("storrent")
    val pwpPeer = system.actorOf(PwpPeer.props(torrent, 7778))

  }

  def main(args: Array[String]): Unit = {
    Torrent(Files.readAllBytes(Paths.get(args(0)))) match {
      case Success(torrent) =>
        start(torrent)

      case Failure(e) =>
        e.printStackTrace()
        System.exit(1)

    }
  }

}
