package storrent

import java.nio.file.{ Files, Paths }

import org.scalatest.{ FunSuite, Matchers }

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 11:13
 */
class TorrentTest extends FunSuite with Matchers {

  import sbencoding._

  def file(path: String) = {
    new String(Files.readAllBytes(Paths.get(path)), "ISO-8859-1")
  }

  test("parse torrents") {
    val raw = file("src/test/resources/torrents/9FE44783704319D9DBAE418F745A1FB106E45B1F.torrent")
    Torrent(raw).get.raw.toString() shouldEqual raw
  }

}
