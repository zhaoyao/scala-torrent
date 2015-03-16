package storrent


case class Peer(id: String, ip: String, port: Int) {

  /**
  *  The first 4 bytes contain the 32-bit ipv4 address.
  *  The remaining two bytes contain the port number.
  *  Both address and port use network-byte order.
  *  http://www.bittorrent.org/beps/bep_0023.html
  */
  def compact: Array[Byte] = {
      val result = Array.fill[Byte](6)(0)
      ip.split('.').map(_.toByte).copyToArray(result)
      result(4) = ((port >> 8) & 0xff).toByte
      result(5) = (port & 0xff).toByte
      result
  }


}

