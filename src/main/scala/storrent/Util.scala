package storrent

import java.security.MessageDigest

/**
 * User: zhaoyao
 * Date: 3/13/15
 * Time: 14:12
 */
object Util {

  def sha1Hex(value: String) = {
    encodeHex(sha1(value))
  }

  def sha1(value: String) = {
    val digest = MessageDigest.getInstance("sha1")
    digest.update(value.getBytes())
    digest.digest()
  }


  private final val Digits = Array[Char]('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

  private def toDigit( ch : Char, index : Int ) : Int = {
    val digit = Character.digit( ch, 16 )

    if ( digit == -1 ) {
      throw new IllegalArgumentException("Illegal hexadecimal character " + ch + " at index " + index);
    }

    return digit
  }

  /**
   *
   * Turns a HEX based char sequence into a Byte array
   *
   * @param value
   * @param start
   * @return
   */

  def decodeHex( value : CharSequence, start : Int = 0 ) : Array[Byte] = {

    val length = value.length - start
    val end = value.length()

    if ( (length & 0x01) != 0 ) {
      throw new IllegalArgumentException("Odd number of characters. A hex encoded byte array has to be even.")
    }

    val out = new Array[Byte](length >> 1)

    var i = 0
    var j = start

    while ( j < end ) {
      var f = toDigit(value.charAt(j), j) << 4
      j += 1
      f = f | toDigit(value.charAt(j), j)
      j += 1
      out(i) = (f & 0xff).asInstanceOf[Byte]
      i += 1
    }

    out
  }

  /**
   *
   * Encodes a byte array into a String encoded with Hex values.
   *
   * @param bytes
   * @param prefix
   * @return
   */

  def encodeHex( bytes : Array[Byte], prefix : Array[Char] = Array.empty ) : String = {
    val length = (bytes.length * 2) + prefix.length
    val chars = new Array[Char](length)

    if ( prefix.length != 0 ) {
      var x = 0
      while ( x < prefix.length ) {
        chars(x) = prefix(x)
        x += 1
      }
    }

    val dataLength = bytes.length
    var j = prefix.length
    var i = 0

    while ( i < dataLength ) {
      chars(j) = Digits(( 0xF0 & bytes(i)) >>> 4 )
      j += 1
      chars(j) = Digits( 0x0F & bytes(i) )
      j += 1
      i += 1
    }

    new String(chars)
  }
}
