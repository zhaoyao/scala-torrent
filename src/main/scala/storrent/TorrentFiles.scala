package storrent

import java.io.{ File, FileInputStream, FileNotFoundException, RandomAccessFile }
import java.nio.file.{ Files, Paths }
import java.security.MessageDigest

import storrent.TorrentFiles.{ FileLoc, Piece, TorrentFile }

import scala.annotation.tailrec
import scala.collection.mutable

object TorrentFiles {

  case class Piece(idx: Int, hash: Array[Byte], locs: List[FileLoc]) {
    def length = locs.map(_.length).sum
  }

  case class PieceBlock(piece: Int, offset: Int, length: Int)

  case class FileLoc(fileIndex: Int,
                     offset: Long,
                     length: Int)

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

  @tailrec
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
      infoDict._length.map { l =>
        List(TorrentFile(infoDict.name, l, infoDict.md5sum))
      }
    }.getOrElse(throw new RuntimeException("Invalid torrent"))

    val pieceLength = infoDict.pieceLength.toInt
    val totalLength = files.map(_.length).sum
    val lastPieceLength = if (totalLength % pieceLength == 0) pieceLength else (totalLength % pieceLength).toInt
    val totalPieces = if (totalLength % pieceLength == 0) totalLength / pieceLength else (totalLength / pieceLength) + 1

    @tailrec
    def buildLocations(f: TorrentFile,
                       index: Int,
                       offset: Long,
                       files: List[TorrentFile],
                       pieceRemaining: Int,
                       locations: List[FileLoc]): (TorrentFile, List[TorrentFile], Int, Long, List[FileLoc]) = {
      (pieceRemaining, f.length - offset) match {
        case (0, _) => (f, files, index, offset, locations.reverse)

        case (x, fileRemaining) if fileRemaining == 0 =>
          if (f.length == 0) {
            //empty file
            buildLocations(files.head, index + 1, 0, files.tail, x,
              FileLoc(index, 0, 0) :: locations)
          } else {
            buildLocations(files.head, index + 1, 0, files.tail, x, locations)
          }

        case (x, fileRemaining) if x > fileRemaining =>
          buildLocations(files.head, index + 1, 0, files.tail, x - fileRemaining.toInt,
            FileLoc(index, offset, fileRemaining.toInt) :: locations)

        case (x, fileRemaining) =>
          buildLocations(f, index, offset + x, files, 0,
            FileLoc(index, offset, x) :: locations)

      }
    }

    @tailrec
    def buildPieces(f: TorrentFile,
                    index: Int,
                    offset: Long,
                    files: List[TorrentFile],
                    pieceIndex: Int,
                    pieces: List[Array[Byte]],
                    result: List[Piece]): List[Piece] = {
      pieces match {
        case Nil => result.reverse

        case hash :: tail =>
          val pl: Int = if (pieceIndex + 1 == totalPieces) lastPieceLength else pieceLength
          val (file, fs, fileIndex, fileOffset, locs) = buildLocations(f, index, offset, files, pl, Nil)
          buildPieces(file, fileIndex, fileOffset, fs, pieceIndex + 1, tail,
            Piece(pieceIndex, hash, locs) :: result)
      }
    }

    val pieces = buildPieces(files.head, 0, 0, files.tail, 0, infoDict.pieces.sliding(20, 20).toList, Nil)
    TorrentFiles(files, pieces, totalLength)
  }
}

case class TorrentFiles(files: List[TorrentFile],
                        pieces: List[Piece],
                        totalLength: Long) {

  def pieceLength(i: Int): Int = {
    require(i < pieces.length)
    pieces(i).locs.map(_.length).sum
  }

  def locateFiles(index: Int, offset: Int, length: Int): List[FileLoc] = {
    assert(index < pieces.size)

    // (15312, 1024), (15312+1024, 1024), (15312+1024*2, 5)
    @tailrec
    def offsetPair(xs: List[FileLoc], ret: List[(FileLoc, (Int, Int))]): List[(FileLoc, (Int, Int))] = xs match {
      case Nil       => ret
      case h :: tail => offsetPair(tail, (h, (tail.map(_.length).sum, tail.map(_.length).sum + h.length)) :: ret)
    }

    val fileWithOffset = offsetPair(pieces(index).locs.reverse, Nil)

    def in(i: Long, r: (Int, Int)): Boolean = r._1 <= i && i < r._2

    fileWithOffset.filter(p => {
      (in(offset, p._2) || in(offset + length, p._2)) || (offset <= p._2._1 && p._2._2 <= offset + length)
    }) match {
      case Nil => Nil

      case f :: Nil =>
        f._1.copy(offset = f._1.offset + (offset - f._2._1), length = length) :: Nil

      case first :: tail =>
        val last = tail.reverse.head

        val f = first._1.copy(offset = first._1.offset + (offset - first._2._1), length = first._1.length - (offset - first._2._1))
        val l = last._1.copy(length = (offset + length) - last._2._1)

        f :: tail.init.map(_._1) ::: List(l)
    }
  }

  val fileMapping: Map[Int, List[Int]] = {
    // [piece,],[],[]
    val m = mutable.Map[Int, List[Int]]()
    pieces.foreach { piece =>
      piece.locs.foreach { fileLoc =>
        m(fileLoc.fileIndex) = piece.idx :: m.getOrElseUpdate(fileLoc.fileIndex, Nil)
      }
    }
    m.toMap
  }
}