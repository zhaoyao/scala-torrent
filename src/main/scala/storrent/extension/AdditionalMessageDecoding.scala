package storrent.extension

import java.nio.ByteBuffer

import storrent.pwp.Message

import scala.util.Try

/**
 * User: zhaoyao
 * Date: 3/17/15
 * Time: 15:56
 */
trait AdditionalMessageDecoding {

  def parseMessage: PartialFunction[(Byte, ByteBuffer), Try[Message]]

}
