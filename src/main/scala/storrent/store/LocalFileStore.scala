package storrent.store

import java.io._
import java.nio.file.Paths

import storrent.Util
import storrent.store.FileStore.FileObject
import storrent.store.LocalFileStore.LocalFileObject

import scala.concurrent.Future

object LocalFileStore {

  case class LocalFileObject(dataDir: String, file: File) extends FileObject {
    override def path: String = file.getAbsolutePath.substring(new File(dataDir).getAbsolutePath.length + 1)

    override def length: Long = file.length()
  }

}

class LocalFileStore(dataDir: String) extends FileStore {

  override def checksum(fileObject: FileObject): Array[Byte] = {
    val in = new FileInputStream(new File(dataDir, fileObject.path))
    try {
      Util.sha1(in)
    } finally {
      in.close()
    }
  }

  override def read(path: String, offset: Int, length: Int): Array[Byte] = {
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

  override def write(path: String, offset: Int, data: Array[Byte]): Unit = {
    val f = new RandomAccessFile(Paths.get(dataDir, path).toFile, "rw")
    try {
      f.seek(offset)
      f.write(data)
    } finally {
      f.close()
    }
  }

  override def list(filter: FileObject => Boolean): List[FileObject] = {
    Option(new File(dataDir).listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = filter(LocalFileObject(dataDir, pathname))
    })).getOrElse(Array.empty).map(f => LocalFileObject(dataDir, f)).toList
  }

}
