package storrent

import java.net.InetAddress
import java.text.DecimalFormat

/**
 * User: zhaoyao
 * Date: 3/16/15
 * Time: 16:44
 */
object PeerId {

  def apply(): String = {
    // 8 + 4 * 3
    val peerId = "STORRENT" +
      InetAddress.getLocalHost.getHostAddress.split("\\.").map(i => new DecimalFormat("000").format(i.toInt)).mkString

    assert(peerId.length == 20, "Invalid peer id: %s".format(peerId))
    peerId
  }

}
