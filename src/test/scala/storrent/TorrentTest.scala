package storrent

import java.nio.file.{Files, Paths}

import org.apache.commons.io.Charsets
import org.scalatest.{FunSuite, Matchers}

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 11:13
 */
class TorrentTest extends FunSuite with Matchers {

  def file(path: String) = {
    new String(Files.readAllBytes(Paths.get(path)), Charsets.ISO_8859_1)
  }

  test("parse torrents") {
    val raw = file("/Users/zhaoyao/Downloads/9FE44783704319D9DBAE418F745A1FB106E45B1F.torrent")
    Torrent(raw).get.toBencoding shouldEqual raw
  }


}
