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
    val infoDict = torrent.metainfo.info
    val files: List[TorrentFile] = infoDict.files.orElse {
      infoDict.length.map { l =>
        List(TorrentFile(infoDict.name, l, infoDict.md5sum))
      }
    }.getOrElse(throw new RuntimeException("Invalid torrent"))

    val pieceLength = infoDict.pieceLength.toInt
    val totalLength = files.map(_.length).sum

    var fileOffset: Long = 0
    var fileIndex = -1

    def nextFile() = {
      fileIndex += 1
      fileOffset = 0

      if (fileIndex < files.length)
        files(fileIndex)
      else
        null
    }

    var f: TorrentFile = nextFile()

    println(files)

    val pieces = infoDict.pieces.sliding(20, 20).zipWithIndex.map { p =>
      val (pieceHash, i) = p

      var locations = List[FileLoc]()

//      println(s"Piece index=$i")

      if (fileOffset + pieceLength <= f.length) {
        //当前文件可以满足一个piece大小

        locations = List(FileLoc(fileIndex, fileOffset, pieceLength))
        fileOffset += pieceLength

//        println(s"0 fileOffset=$fileOffset fileIndex=$fileIndex")

        if (fileOffset + pieceLength == f.length) {
//          fileOffset = 0
//          fileIndex += 1
          f = nextFile()
        }

      } else {
        var pieceRemaining: Long = Math.min(pieceLength, totalLength - i*pieceLength)

        while (pieceRemaining > 0) {

          if (pieceRemaining > f.length - fileOffset) {
            //当前文件无法满足一个piece大小

            locations ::= FileLoc(fileIndex, fileOffset, f.length - fileOffset)
            pieceRemaining -= (f.length - fileOffset)

//            fileOffset = 0
//            fileIndex += 1
//            f = files(fileIndex)
//            println(s"2 pieceRemaining=$pieceRemaining fileOffset=$fileOffset fileIndex=$fileIndex")
            f = nextFile()

          } else {
            //当前文件可以满足一个piece大小
            locations ::= FileLoc(fileIndex, fileOffset, pieceRemaining)
            fileOffset += pieceRemaining
            pieceRemaining = 0

//            println(s"3 pieceRemaining=$pieceRemaining fileOffset=$fileOffset fileIndex=$fileIndex")

            if (fileOffset == f.length) {
//              fileIndex += 1
//              fileOffset = 0
              f = nextFile()     //8000597
            }

          }
        }
      }

      Piece(i, pieceHash, locations.reverse)
    }.toList

    TorrentFiles(files, pieces, totalLength)
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