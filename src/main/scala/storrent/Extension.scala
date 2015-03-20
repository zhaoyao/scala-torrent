package storrent

import java.nio.ByteBuffer

import storrent.pwp.{ Handshake, Message }

/**
 * User: zhaoyao
 * Date: 3/17/15
 * Time: 15:01
 */
trait Extension {

  def name: String

  def isEnabled(hs: Handshake)

  def parseMessage: PartialFunction[(Byte, ByteBuffer), Message]

}
