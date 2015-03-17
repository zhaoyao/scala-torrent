package storrent.extension

import storrent.pwp.Handshake

case class ReservedBit(i: Int, value: Byte)

trait HandshakeEnabled {

  def reservedBit: ReservedBit

  def isEnabled(hs: Handshake) = (hs.reserved(reservedBit.i) & reservedBit.value) == 1

}
