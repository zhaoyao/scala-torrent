package storrent

import java.net.InetAddress

import org.scalatest.{ Matchers, WordSpec, FunSuite }

/**
 * User: zhaoyao
 * Date: 3/16/15
 * Time: 18:57
 */
class PeerSpec extends WordSpec with Matchers {

  "Peer compact encoding" should {

    "Encode successfully" in {

      Peer("", "192.168.1.1", 20).compact shouldEqual
        InetAddress.getByName("192.168.1.1").getAddress ++ Array((20 >> 8 & 0xff).toByte, (20 & 0xff).toByte)

    }

    "Decode successfully" in {
      val p = Peer.parseCompact(InetAddress.getByName("192.168.1.1").getAddress ++ Array((20 >> 8 & 0xff).toByte, (20 & 0xff).toByte))

      p.id shouldEqual ""
      p.ip shouldEqual "192.168.1.1"
      p.port shouldEqual 20
    }

  }

}
