package storrent

import org.scalatest.{FunSuite, Matchers}
import storrent.bencode.{BencodeDecoder, BencodeEncoder}

import scala.util.{Success, Failure}

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 11:39
 */
class BencodingTest extends FunSuite with Matchers {

  test("Decode nested dict and list") {
    val d = Map(
      "announce" -> "udp://open.demonii.com:1337",
      "announce-list" ->
        List(
          List("udp://open.demonii.com:1337"),
          List("udp://tracker.coppersurfer.tk:6969"),
          List("udp://tracker.leechers-paradise.org:6969"),
          List("udp://exodus.desync.com:6969")),
      "info" -> Map(
        "name" -> "d",
        "piece length" -> 20,
        "pieces" -> List("a", "b", "c", "d"),
        "files" -> List(
          Map(
            "path" -> "/a/b/c",
            "length" -> 29
          )
        )
      ))

    val encode: String = BencodeEncoder.encode(d)
    println(encode)
    BencodeDecoder.decode(encode).get shouldEqual d
  }

}
