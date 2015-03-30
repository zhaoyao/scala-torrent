package storrent.store

import java.io.{File, FileFilter}

import storrent.store.FileStore.FileObject

import scala.concurrent.Future

object FileStore {

  trait FileObject {
    def path: String
    def length: Long
  }

}

trait FileStore {

  def list(filter: FileObject => Boolean): List[FileObject]

  def checksum(fileObject: FileObject): Array[Byte]

  def read(path: String, offset: Int, length: Int): Array[Byte]

  def write(path: String, offset: Int, data: Array[Byte]): Unit

}
