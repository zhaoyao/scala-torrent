package storrent.pwp

import java.nio.ByteBuffer

/**
 * User: zhaoyao
 * Date: 3/16/15
 * Time: 15:26
 */
trait MessageSpecBase {

  def encodedMsg(id: Byte) = {
    ByteBuffer.wrap(Array[Byte](0, 0, 0, 1, id))
  }

  def encodedMsg(id: Byte, payloadLength: Int)(f: ByteBuffer => Unit) = {
    val b = ByteBuffer.allocate(4 + 1 + payloadLength)
    b.putInt(1 + payloadLength)
    b.put(id)
    f(b)
    b.rewind()
    b
  }

}
