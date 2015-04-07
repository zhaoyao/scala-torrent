package storrent.client

import java.io._
import java.net.URI
import java.nio.file.Files
import java.util

import org.slf4j.LoggerFactory
import storrent.TorrentFiles.{ Piece, TorrentFile }
import storrent.{ TorrentFiles, Torrent, Util }

import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object TorrentStore {

  val logger = LoggerFactory.getLogger("TorrentStore")

  def apply(torrent: Torrent, uri: String) = {
    Try(URI.create(uri)) match {
      case Success(parsedUri) => parsedUri.getScheme match {
        case "file" =>
          new LocalFilesystem(torrent.files, parsedUri.getPath)
      }

      case Failure(e) =>
        logger.error("Failed to create TorrentStore using uri: " + uri, e)
        throw e
    }
  }

}

trait TorrentStore {

  //  def torrent: Torrent

  def resume(): Map[Int, List[PieceBlock]]

  def mergeBlocks(pieceIndex: Int): Either[Piece, List[PieceBlock]]

  def mergePieces()

  def writePiece(piece: Int, offset: Int, data: Array[Byte]): Try[Boolean]

  def readPiece(piece: Int, offset: Int, length: Int): Try[Option[Array[Byte]]]

}

object LocalFilesystem {

}

/**
 *  *                                                           TorrentStore
 *  *                                    +-----------------------------------------------------------------+
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    |      block                                                      |
 *  *             writePiece(0, 0, 512)  |   +------------------+                                          |
 *  *              +-----------------------> |  .0.0.512.blk    +---+    merge blocks                      |
 *  *                                    |   +------------------+   +---------------+                      |
 *  *                                    |   +------------------+   |               |                      |
 *  *                                    |   |  .0.512.512.blk  +---+               |                      |
 *  *                                    |   +------------------+                   |                      |
 *  *                                    |                                          |                      |
 *  *                                    |                                          |                      |
 *  *                                    |                                          |                      |
 *  *                                    |                                          |                      |
 *  *                                    |                                          |                      |
 *  *                                    |                                          v                      |
 *  *                                    |                                     +-------------------+       |
 *  *              readPiece(0, 0, 654)  |                                     | .0.piece          |       |
 *  *            <-------------------------------------------------------------+                   |       |
 *  *                                    |                                     +-------------------+       |
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    |                                                                 |
 *  *                                    +-----------------------------------------------------------------+
 *  *
 */
class LocalFilesystem(val files: TorrentFiles,
                      dataDir: String) extends TorrentStore {

  val logger = LoggerFactory.getLogger(this.getClass)

  if (!new File(dataDir).exists()) {
    new File(dataDir).mkdirs()
  }

  val pieceFileRegex = "\\.(\\d+)\\.piece$".r
  val blockFileRegex = "\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.blk$".r

  /**
   * 返回结果分为以下三种情况
   *
   * piece => blocks
   *
   */
  override def resume(): Map[Int, List[PieceBlock]] = try {
    files.pieces.foreach(p => mergeBlocks(p.idx))
    mergePieces()
    mergeTmpFiles()

    //下载完成的文件
    val downloadedFiles = Util.listFiles(dataDir, rec = true)({ f =>
      val fname = f.getName
      !fname.startsWith(".") && !fname.endsWith(".st")
    }).map(dropPrefixOrSuffix).toSet

    logger.debug(s"Found completed files: $downloadedFiles")
    if (files.files.map(f => (f.path, f.length)).toSet == downloadedFiles) {
      //完全下载完毕
      files.pieces.map(p => (p.idx, List(PieceBlock(p.idx, 0, p.length)))).toMap

    } else {

      val result = mutable.Map[Int, Set[PieceBlock]]().withDefault(_ => Set.empty)

      //中间文件
      Util.listFiles(dataDir, rec = true)(_ => true).foreach { f =>
        f.getAbsoluteFile.getName match {
          case blockFileRegex(pieceIndex, offset, length) =>
            result(pieceIndex.toInt) ++= Set(PieceBlock(pieceIndex.toInt, offset.toInt, length.toInt))

          case pieceFileRegex(pieceIndex) =>
            val piece = files.pieces(pieceIndex.toInt)
            val pieceLength = piece.locs.map(_.length).sum
            val fileLength = f.length

            if (pieceLength == fileLength && util.Arrays.equals(checksum(f), piece.hash)) {
              result(pieceIndex.toInt) = Set(PieceBlock(piece.idx, 0, piece.length.toInt))
            } else {
              // remove piece
              f.delete()
            }

          case filename if filename.endsWith(".st") =>
            val raf = new RandomAccessFile(f, "r")
            try {
              for ((tf, index) <- torrentFile(f)) {
                files.fileMapping(index).foreach { pieceIndex =>
                  if (raf.readInt() == 1) {
                    val locsContainFiles = files.pieces(pieceIndex).locs.filter(_.fileIndex == index)
                    locsContainFiles.zipWithIndex.foreach { p =>
                      val (fileLoc, i) = p
                      val pieceOffset = locsContainFiles.take(i).map(_.length).sum.toInt
                      result(pieceIndex) = Set(PieceBlock(pieceIndex, pieceOffset, fileLoc.length.toInt))
                    }
                  }
                }
              }
            } finally raf.close()

          case filename if torrentFile(f).nonEmpty =>
            if (torrentFile(f).get._1.length == f.length()) {
              for ((tf, index) <- torrentFile(f)) {
                files.fileMapping(index).foreach { pieceIndex =>
                  val locsContainFiles = files.pieces(pieceIndex).locs.filter(_.fileIndex == index)
                  locsContainFiles.zipWithIndex.foreach { p =>
                    val (fileLoc, i) = p
                    val pieceOffset = locsContainFiles.take(i).map(_.length).sum.toInt
                    result(pieceIndex) = Set(PieceBlock(pieceIndex, pieceOffset, fileLoc.length.toInt))
                  }
                }
              }

            } else {
              f.delete()
            }

        }
      }

      result.map(p => (p._1, p._2.toList)).toMap
    }
  } catch {
    case NonFatal(e) => e.printStackTrace(); Map.empty
  }

  private def torrentFile(f: File): Option[(TorrentFile, Int)] = files.files.zipWithIndex.find(_._1.path == dropPrefixOrSuffix(f)._1)

  override def writePiece(piece: Int, offset: Int, data: Array[Byte]): Try[Boolean] =
    Try {
      require(piece < files.pieces.size, s"piece index $piece out of bounds")
      require(offset + data.length <= files.pieceLength(piece), s"piece data overflow")

      // check offset overlay
      require(pieceBlockFiles(piece).map { f =>
        f.getName match {
          case blockFileRegex(piece, offset, length) => offset.toInt until offset.toInt + length.toInt
        }
      }.forall { bounds =>
        val ret = !bounds.tail.contains(offset) && !bounds.contains(offset + data.length)
        if (!ret) {
          logger.warn("Bounds {}", Array(bounds.head, bounds.last, offset, offset + data.length))
        }
        ret
      }, "block overlay found")

      val bf: File = blockFile(piece, offset, data.length)
      if (bf.exists()) {
        false
      } else {
        val f = new RandomAccessFile(bf, "rw")

        try {
          f.write(data)
          true
        } finally {
          f.close()
        }
      }

    }.recoverWith {
      case NonFatal(e) =>
        e.printStackTrace()
        blockFile(piece, offset, data.length).delete()
        logger.warn("Failed to write block", e)
        Failure(e)
    }

  /**
   * 尝试merge指定piece的所有block，merge成功，生成piece file，并删除原有block文件
   * block文件缺失，返回待下载的block
   * piece校验失败，删除所有block文件，并返回所有待下载block
   */
  def mergeBlocks(pieceIndex: Int): Either[Piece, List[PieceBlock]] = {
    maybeMergeBlocks(pieceIndex) match {
      case Left(Some(mergedPieceFile)) =>
        val piece = files.pieces(pieceIndex)
        //        mergePieceFile(piece, mergedPieceFile)
        Left(piece)

      case Left(None) =>
        //计算剩余blocks
        val r = pieceBlockFiles(0).foldLeft((0, List[PieceBlock]())) { (p, blkFile) =>
          val (lastLength, ret) = p
          blkFile.getName match {
            case blockFileRegex(_, offset, length) =>
              if (lastLength != offset.toInt) {
                (offset.toInt + length.toInt, ret ::: List(PieceBlock(pieceIndex, lastLength, offset.toInt - lastLength)))
              } else {
                (offset.toInt + length.toInt, ret)
              }
          }
        }

        val result = r._2
        Right(if (r._1 != files.pieceLength(pieceIndex)) {
          result ::: List(PieceBlock(pieceIndex, r._1, files.pieceLength(pieceIndex) - r._1))
        } else {
          result
        })

      case Right(invalidBlockFiles) =>
        // have complete pieces, but not valid
        // remove all blocks and re-request
        logger.debug("Remove invalid piece blocks: {}", invalidBlockFiles)
        invalidBlockFiles.foreach(_.delete())

        //TODO 划分成较小的block
        Right(List(PieceBlock(pieceIndex, 0, files.pieceLength(pieceIndex))))
    }
  }

  override def mergePieces(): Unit = {
    pieceFiles().foreach { f =>
      val pieceIndex = f.getName match {
        case pieceFileRegex(pieceIndex) => pieceIndex.toInt
        case _                          => throw new IllegalStateException()
      }

      mergePieceFile(files.pieces(pieceIndex), f)
    }
  }

  def mergeTmpFiles() = {
    Util.listFiles(dataDir, rec = true)({ f => f.getName.endsWith(".st") }).foreach { f =>
      for ((tf, index) <- torrentFile(f)) {
        val raf = new RandomAccessFile(f, "r")
        try {
          if (renameTempFileIfCompleted(files.fileMapping(index), f, raf, tf.path)) {
            logger.info("Merged temp file to {}", tf.path)
          }
        } finally raf.close()
      }
    }
  }

  private def mergePieceFile(piece: Piece, pieceFile: File): Unit = {
    if (!pieceFile.exists()) return

    val in = new RandomAccessFile(pieceFile, "r")

    try {
      var pos = 0l
      piece.locs.foreach { fileLoc =>
        if (new File(dataDir, files.files(fileLoc.fileIndex).path).exists()) {

        } else {
          val tmpF = tmpFile(fileLoc.fileIndex, ensureExist = true)
          logger.debug(s"Writing $piece to ${tmpF.getName}")

          val out = new RandomAccessFile(tmpF, "rw")
          val headerLength = files.fileMapping(fileLoc.fileIndex).size * 4

          try {
            // mark piece
            val markPos = files.fileMapping(fileLoc.fileIndex).indexOf(piece.idx)
            if (markPos < 0) {
              throw new IllegalStateException("File not contains piece ?")
            }
            out.seek(markPos * 4)
            val done = out.readInt == 1

            if (!done) {
              out.seek(headerLength + fileLoc.offset)
              // copy data
              in.getChannel.transferTo(pos, fileLoc.length, out.getChannel)
              out.seek(markPos * 4)
              out.writeInt(1)
            }

            // check piece markers
            if (renameTempFileIfCompleted(files.fileMapping(fileLoc.fileIndex), tmpF, out, files.files(fileLoc.fileIndex).path)) {
              tmpF.delete()
            }

          } finally {
            out.close()
          }
        }

        pos += fileLoc.length
      }
    } finally in.close()

    pieceFile.delete()
  }

  def renameTempFileIfCompleted(pieces: List[Int],
                                tmpFile: File,
                                raf: RandomAccessFile,
                                renameTo: String): Boolean = {
    val headerLength = pieces.size * 4
    raf.seek(0)

    val markers = pieces.map(_ => raf.readInt())
    logger.info(s"Checking ${tmpFile.getName}: $markers")

    if (markers.forall(_ == 1)) {
      // create real file
      val realFile = new File(dataDir, renameTo)

      // 与临时文件在同一层，无需检查文件夹是否存在了
      val rraf = new RandomAccessFile(realFile, "rw")
      try {
        raf.getChannel.transferTo(headerLength, raf.length() - headerLength, rraf.getChannel) // transferTo 目标和本身是同一个channel的时候会死锁？
        logger.debug(s"Renamed $tmpFile to $renameTo")
        true
      } finally rraf.close()

    } else {
      false
    }
  }

  def tmpFile(fileIndex: Int, ensureExist: Boolean): File = {
    val f = new File(dataDir, files.files(fileIndex).path + ".st")
    if (!f.getParentFile.exists()) {
      f.getParentFile.mkdirs()
    }

    if (!f.exists() && ensureExist) {
      //write piece stat header
      val raf = new RandomAccessFile(f, "rw")
      try {
        files.fileMapping(fileIndex).foreach(_ => raf.writeInt(-1))
      } finally {
        raf.close()
      }
    }
    f
  }

  override def readPiece(pieceIndex: Int, offset: Int, length: Int): Try[Option[Array[Byte]]] = Try {
    require(offset + length <= files.pieceLength(pieceIndex), "Piece block overflow")

    val dataFile = pieceFile(pieceIndex)
    if (!dataFile.exists()) {
      // try .st
      val fl = files.locateFiles(pieceIndex, offset, length)

      var data = Array.empty[Byte]

      fl.foreach { fileLoc =>
        if (tmpFileContainsPiece(fileLoc.fileIndex, pieceIndex)) {
          // .st temp file
          data ++= Util.readFile(tmpFile(fileLoc.fileIndex, ensureExist = false),
            files.fileMapping(fileLoc.fileIndex).size * 4 + fileLoc.offset, fileLoc.length)
        } else if (new File(dataDir, files.files(fileLoc.fileIndex).path).exists()) {
          // original file
          data ++= Util.readFile(new File(dataDir, files.files(fileLoc.fileIndex).path), fileLoc.offset, fileLoc.length)
        }
      }

      if (data.isEmpty) None else {
        require(data.length == length)
        Some(data)
      }
    } else {
      val f = new RandomAccessFile(dataFile, "rw")
      try {
        f.seek(offset)
        val b = new Array[Byte](length)
        f.readFully(b)
        Some(b)
      } finally {
        f.close()
      }
    }
  }

  private def tmpFileContainsPiece(fileIndex: Int, pieceIndex: Int) = {
    val f = tmpFile(fileIndex, ensureExist = false)
    if (f.exists()) {
      val raf = new RandomAccessFile(f, "r")
      val pieceOffset = files.fileMapping(fileIndex).indexOf(pieceIndex) * 4
      require(pieceOffset >= 0)

      try {
        raf.seek(pieceOffset)
        raf.readInt() == 1
      } finally raf.close()
    } else {
      false
    }
  }

  /**
   * try to merge block files of specify piece. When we have all blocks of the piece and the piece hash matches,
   * return
   */
  private def maybeMergeBlocks(piece: Int): Either[Option[File], Array[File]] = {
    val pf = pieceFile(piece)
    if (pf.exists()) {
      val in = new FileInputStream(pf)
      try {
        if (validatePiece(in, piece)) {
          return Left(Some(pf))
        }
      } finally in.close()

      //remove invalid piece file
      pf.delete()
    }

    val blkFiles = pieceBlockFiles(piece)

    val blockDownloaded = blkFiles.map(_.length()).sum
    val pieceLength = files.pieceLength(piece)

    //have whole piece
    if (blockDownloaded == pieceLength) {
      import Util._

      def blocksInputStream() = blkFiles.map(new FileInputStream(_)).foldLeft(EmptyInputStream)(_ ++ _)

      if (validatePiece(blocksInputStream(), piece)) {
        //piece hash matches
        Files.copy(blocksInputStream(), pf.toPath)
        blkFiles.foreach(_.delete())
        logger.debug(s"Merged ${blkFiles.map(_.getName).toList} to $pf")

        Left(Some(pieceFile(piece)))
      } else {
        // contains invalid blocks
        Right(blkFiles)
      }

    } else {
      Left(None)
    }
  }

  private def validatePiece(in: InputStream, pieceIndex: Int): Boolean =
    util.Arrays.equals(Util.sha1(in), files.pieces(pieceIndex).hash)

  private def pieceBlockFiles(piece: Int) = Option(new File(dataDir).listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = s"\\.$piece\\.\\d+\\.\\d+\\.blk$$".r.pattern.matcher(name).find()
  })).getOrElse(Array.empty[File]).sortBy(f => f.getName match {
    case blockFileRegex(_, offset, _) => offset.toInt
  })

  private def pieceFiles() = Option(new File(dataDir).listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = pieceFileRegex.pattern.matcher(name).find()
  })).getOrElse(Array.empty).sortBy(_.getName match {
    case pieceFileRegex(pieceIndex) => pieceIndex.toInt
  })

  private def pieceFile(piece: Int): File = new File(dataDir, ".%d.piece".format(piece))

  private def blockFile(piece: Int, block: Int, length: Int) = new File(dataDir, ".%d.%d.%d.blk".format(piece, block, length))

  private def checksum(f: File): Array[Byte] = {
    val in = new FileInputStream(f)
    try {
      Util.sha1(in)
    } finally {
      in.close()
    }
  }

  private def dropPrefixOrSuffix(f: File) = {
    val dirLen = if (dataDir.endsWith("/")) dataDir.length else dataDir.length + 1
    val pathname = f.getAbsolutePath.substring(dirLen)
    if (pathname.endsWith(".st")) {
      (pathname.dropRight(3), f.length())
    } else {
      (pathname, f.length())
    }
  }

}