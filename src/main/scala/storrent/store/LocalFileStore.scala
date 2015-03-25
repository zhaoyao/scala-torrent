package storrent.store

import java.io.{EOFException, RandomAccessFile}
import java.nio.file.Paths

import scala.concurrent.Future

/**
 * User: zhaoyao
 * Date: 3/25/15
 * Time: 17:30
 */
class LocalFileStore(dataDir: String) extends FileStore {

  override def read(path: String, offset: Int, length: Int): Future[Array[Byte]] = Future {
    val f = new RandomAccessFile(Paths.get(dataDir, path).toFile, "rw")
    val data = new Array[Byte](length.toInt)
     try {
       f.seek(offset)
       val read = f.read(data)
       if (read != length) {
         throw new EOFException("Not enough data")
       }
     } finally {
       f.close()
     }
    data
  }

  override def write(path: String, offset: Int, data: Array[Byte]): Future[Boolean] = Future {
    val f = new RandomAccessFile(Paths.get(dataDir, path).toFile, "rw")
    try {
      f.seek(offset)
      f.write(data)
    } finally {
      f.close()
    }
    true
  }
}
