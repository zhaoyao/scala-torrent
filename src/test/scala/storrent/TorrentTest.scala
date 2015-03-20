package storrent

import java.nio.file.{ Files, Paths }

import org.scalatest.{ FunSuite, Matchers }

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 11:13
 */
class TorrentTest extends FunSuite with Matchers {

  def readFile(path: String) = Files.readAllBytes(Paths.get(path))

  test("parse torrents") {
    val raw = readFile("src/test/resources/torrents/9FE44783704319D9DBAE418F745A1FB106E45B1F.torrent")
    val parsed = Torrent(raw).get.raw.toByteArray()
    parsed shouldEqual raw
  }

}
