package storrent

import java.io.{ File, FileInputStream, FileNotFoundException, RandomAccessFile }
import java.nio.ByteBuffer
import java.security.MessageDigest

import storrent.TorrentFiles.{ Piece, TorrentFile }

object TorrentFiles {

  case class Piece(idx: Int, hash: Array[Byte], locs: List[FileLoc])

  case class FileLoc(fileIndex: Int,
                     offset: Long, length: Long)

  case class TorrentFile(path: String,
                         length: Long,
                         md5sum: Option[Array[Byte]])

  def calcFileMd5(file: File): Array[Byte] = {
    val md = MessageDigest.getInstance("MD5")

    val is: FileInputStream = new FileInputStream(file)

    try {
      val b = new Array[Byte](1024 * 1024)
      var len = is.read(b)
      while (len > 0) {
        md.update(b, 0, len)
        len = is.read(b)
      }
    } finally {
      is.close()
    }

    md.digest()
  }

  def fromLocalFiles(path: String,
                     pieceLength: Int = 256 * 1024,
                     filesMd5Sum: Boolean = true): TorrentFiles = {
    val targetFile = new File(path)
    if (!targetFile.exists()) {
      throw new FileNotFoundException(path)
    }

    if (targetFile.isFile) {
      val file = TorrentFile(targetFile.getName, targetFile.length(), Some(calcFileMd5(targetFile)))
      TorrentFiles(
        List(file),
        genPieces(
          targetFile.getParent,
          List(file),
          pieceLength, 0,
          0, 0,
          new Array[Byte](pieceLength),
          Nil,
          Nil),
        targetFile.length()
      )

    } else {
      def walkFiles(f: File, files: List[File]): List[File] = {
        if (f.isFile) {
          f :: files
        } else {
          f.listFiles().foldLeft(files) { (xs, f) =>
            walkFiles(f, xs)
          }
        }
      }

      val files = walkFiles(targetFile, Nil).sorted.map { f =>
        val relativePath = f.toPath.subpath(targetFile.toPath.getNameCount, f.toPath.getNameCount)

        if (filesMd5Sum) {
          TorrentFile(
            relativePath.toString,
            f.length(),
            Some(calcFileMd5(f))
          )
        } else TorrentFile(relativePath.toString, f.length(), None)
      }

      val totalLength = files.map(_.length).sum
      val pieces = genPieces(
        targetFile.getAbsolutePath,
        files, pieceLength, 0, 0, 0, new Array[Byte](pieceLength), Nil, Nil)

      TorrentFiles(files, pieces, totalLength)
    }
  }

  def genPieces(basePath: String,
                files: List[TorrentFile],
                pieceLength: Int,
                currPieceLength: Int,
                currFileIndex: Int,
                currFileOffset: Long,
                buffer: Array[Byte],
                pieces: List[Piece],
                fileLocs: List[FileLoc]): List[Piece] = {
    files match {
      case Nil =>
        if (currPieceLength > 0) {
          (Piece(pieces.length, pieceHash(buffer, currPieceLength), fileLocs.reverse) :: pieces).reverse
        } else {
          pieces.reverse
        }

      case _ =>
        val head = files.head
        val in = new RandomAccessFile(new File(basePath, head.path), "r")
        if (in.length() < currFileOffset) {
          throw new IllegalStateException("File offset overflow")
        }
        in.seek(currFileOffset)

        val fileRemaining = in.length() - currFileOffset;
        val currentPieceNeed = pieceLength - currPieceLength

        if (fileRemaining >= currentPieceNeed) {
          val n = in.read(buffer, currPieceLength, currentPieceNeed)
          assert(n == currentPieceNeed)

          // assemble file locations
          // how to assemble prev file locs?

          //next piece
          in.close()
          return genPieces(basePath, files,
            pieceLength, 0,
            currFileIndex,
            currFileOffset + currentPieceNeed,
            buffer,
            Piece(pieces.length, pieceHash(buffer, pieceLength),
              (FileLoc(currFileIndex, currFileOffset, currentPieceNeed) :: fileLocs).reverse
            ) :: pieces,
            Nil
          )
        } else {
          // current file does not satisfy current piece
          in.read(buffer, currPieceLength, fileRemaining.toInt)

          in.close()
          return genPieces(basePath, files.tail, pieceLength,
            currPieceLength + fileRemaining.toInt,
            currFileIndex + 1,
            0,
            buffer,
            pieces,
            (FileLoc(currFileIndex, currFileOffset, fileRemaining.toInt) :: fileLocs).reverse
          )
        }
    }

  }

  def pieceHash(b: Array[Byte], len: Int): Array[Byte] = {
    val md = MessageDigest.getInstance("sha1")
    md.update(b, 0, len)
    md.digest()
  }

  def fromMetainfo(torrent: Torrent): TorrentFiles = {
    null
  }
}

case class TorrentFiles(files: List[TorrentFile],
                        pieces: List[Piece],
                        totalLength: Long) {

  def readPiece(index: Int, offset: Int, length: Int): Unit = {
    val buf = ByteBuffer.allocate(length)

  }

  def writePiece(index: Int, offset: Int, data: Array[Int]) = {

  }

  private def locateFileByIndex(index: Int, offset: Int): Unit = {

  }
}