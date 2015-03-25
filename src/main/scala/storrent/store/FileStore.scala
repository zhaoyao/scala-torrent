package storrent.store

import scala.concurrent.Future

/**
 * User: zhaoyao
 * Date: 3/25/15
 * Time: 17:27
 */
trait FileStore {

  def read(path: String, offset: Int, length: Int): Future[Array[Byte]]

  def write(path: String, offset: Int, data: Array[Byte]): Future[Boolean]

}
