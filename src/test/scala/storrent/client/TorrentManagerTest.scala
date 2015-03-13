package storrent.client

import java.nio.file.{Paths, Files}

import akka.actor._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import org.scalatest._
import storrent.Torrent
import storrent.client.TorrentManager.{TorrentStarted, StartTorrent}

import scala.concurrent.duration.DurationInt

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 14:50
 */
class TorrentManagerTest(_system: ActorSystem) extends TestKit(_system)
  with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("TorrentManagerTest"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def loadFile(path: String): String = {
    new String(Files.readAllBytes(Paths.get(path)), "ISO-8859-1")
  }

  "A TorrentManager actor" must {

    "create TorrentClient when info hash not seen" in {
      val mgr = TestActorRef[TorrentManager](Props(new TorrentManager(null)))

      mgr.underlyingActor.torrentActors.size shouldBe 0

      val metainfoString = loadFile("src/test/resources/torrents/9FE44783704319D9DBAE418F745A1FB106E45B1F.torrent")
      val infoHash = Torrent(metainfoString).get.info.hash
      mgr ! StartTorrent(metainfoString)

      expectMsgPF(1.seconds) {
        case TorrentStarted(_) => true
      }

      mgr.underlyingActor.torrentActors.size shouldBe 1
      mgr.underlyingActor.torrentActors should contain key infoHash

      //start again but not create again
      mgr ! StartTorrent(metainfoString)
      expectMsgPF(1.seconds) {
        case TorrentStarted(_) => true
      }
      mgr.underlyingActor.torrentActors.size shouldBe 1
      mgr.underlyingActor.torrentActors should contain key infoHash

    }

    "retain client states" in {
      val mgr = TestActorRef[TorrentManager](Props(new TorrentManager(null)))

      mgr.underlyingActor.torrentActors.size shouldBe 0

      val metainfoString = loadFile("src/test/resources/torrents/9FE44783704319D9DBAE418F745A1FB106E45B1F.torrent")
      val infoHash = Torrent(metainfoString).get.info.hash
      mgr ! StartTorrent(metainfoString)

      var client: ActorRef = null
      expectMsgPF(1.seconds) {
        case TorrentStarted(c) => client = c
      }

      mgr.underlyingActor.torrentActors.size shouldBe 1
      mgr.underlyingActor.torrentActors should contain key infoHash

      client should not be null

      client ! Kill

      awaitCond({
        mgr.underlyingActor.torrentActors.size == 0
      }, 2.seconds)
    }
  }
}
