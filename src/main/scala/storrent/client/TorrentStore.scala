package storrent.client

import java.io._
import java.net.URI
import java.nio.file.Files
import java.util

import org.slf4j.LoggerFactory
import storrent.TorrentFiles.Piece
import storrent.{ Torrent, Util }

import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object TorrentStore {

  val logger = LoggerFactory.getLogger("TorrentStore")

  def apply(torrent: Torrent, uri: String) = {
    Try(URI.create(uri)) match {
      case Success(parsedUri) => parsedUri.getScheme match {
        case "file" =>
          new LocalFilesystem(torrent, parsedUri.getPath)
      }

      case Failure(e) =>
        logger.error("Failed to create TorrentStore using uri: " + uri, e)
        throw e
    }
  }

}

trait TorrentStore {

  def torrent: Torrent

  def resume: Map[Piece, List[PieceBlock]]

  def writePiece(piece: Int, offset: Int, data: Array[Byte]): Try[Boolean]

  def readPiece(piece: Int, offset: Int, length: Int): Option[Array[Byte]]

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
class LocalFilesystem(val torrent: Torrent,
                      dataDir: String) extends TorrentStore {

  val logger = LoggerFactory.getLogger(this.getClass)

  val pieceFileRegex = "\\.(\\d+)\\.piece$".r
  val blockFileRegex = "\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.blk$".r

  if (!new File(dataDir).exists()) {
    new File(dataDir).mkdirs()
  }

  /**
   * 返回结果分为以下三种情况
   *
   * piece => blocks
   *
   */
  override def resume: Map[Piece, List[PieceBlock]] = {
    val result = mutable.Map[Piece, List[PieceBlock]]().withDefault(_ => Nil)

    Option(new File(dataDir).listFiles).getOrElse(Array.empty).foreach { f =>
      f.getName match {
        case blockFileRegex(pieceIndex, offset, length) =>
          result(torrent.files.pieces(pieceIndex.toInt)) ++= List(PieceBlock(pieceIndex.toInt, offset.toInt, length.toInt))

        case pieceFileRegex(pieceIndex) =>
          val piece = torrent.files.pieces(pieceIndex.toInt)
          val pieceLength = piece.locs.map(_.length).sum
          val fileLength = f.length

          if (pieceLength == fileLength && util.Arrays.equals(checksum(f), piece.hash)) {
            result(piece) = List(PieceBlock(piece.idx, 0, piece.length.toInt))
          } else {
            // remove piece
            f.delete()
          }

        case _ =>
      }
    }

    result.toMap
  }

  override def writePiece(piece: Int, offset: Int, data: Array[Byte]): Try[Boolean] =
    Try {
      require(piece < torrent.files.pieces.size, s"piece index $piece out of bounds")
      require(offset + data.length <= torrent.files.pieceLength(piece), s"piece data overflow")

      // check offset overlay
      require(pieceBlockFiles(piece).map { f =>
        f.getName match {
          case blockFileRegex(piece, offset, length) => (offset.toInt until offset.toInt + length.toInt)
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
          if (f.length() == 0) {
            //pre-allocate file
            f.setLength(torrent.files.pieceLength(piece))
          }
          f.seek(offset)
          f.write(data)
          true
        } finally {
          f.close()
        }
      }

    }.recoverWith {
      case NonFatal(e) =>
        blockFile(piece, offset, data.length).delete()
        logger.warn("Failed to write block", e)
        Failure(e)
    }

  /**
   * 尝试merge指定piece的所有block，merge成功，生成piece file，并删除原有block文件
   * block文件缺失，返回待下载的block
   * piece校验失败，删除所有block文件，并返回所有待下载block
   */
  def mergeBlocks(piece: Int): Either[Piece, List[PieceBlock]] = {
    maybeMergeBlocks(piece) match {
      case Left(Some(mergedPieceFile)) =>
        logger.debug("Merged blocks file to piece: {}", mergedPieceFile)
        Left(torrent.files.pieces(piece))

      case Left(None) =>
        logger.warn("Incomplete piece: {}", piece)
        Right(Nil)

      case Right(invalidBlockFiles) =>
        // have complete pieces, but not valid
        // remove all blocks and re-request
        logger.debug("Remove invalid piece blocks: {}", invalidBlockFiles)
        invalidBlockFiles.foreach(_.delete())

        //TODO 划分成较小的block
        Right(List(PieceBlock(piece, 0, torrent.files.pieceLength(piece))))
    }
  }

  override def readPiece(piece: Int, offset: Int, length: Int): Option[Array[Byte]] = {
    val dataFile = pieceFile(piece)
    if (!dataFile.exists()) {
      None
    } else {
      val f = new RandomAccessFile(dataFile, "rw")
      try {
        f.seek(offset)
        val b = new Array[Byte](length)
        f.read(b)
        Some(b)
      } finally {
        f.close()
      }
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
    val pieceLength = torrent.files.pieceLength(piece)
    logger.debug("Piece %d downloaded %d total %d".format(piece, blockDownloaded, pieceLength))

    //have whole piece
    if (blockDownloaded == pieceLength) {
      import Util._

      def blocksInputStream() = blkFiles.map(new FileInputStream(_)).foldLeft(EmptyInputStream)(_ ++ _)

      if (validatePiece(blocksInputStream(), piece)) {
        //piece hash matches
        Files.copy(blocksInputStream(), pf.toPath)
        blkFiles.foreach(_.delete())
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
    util.Arrays.equals(Util.sha1(in), torrent.files.pieces(pieceIndex).hash)

  private def pieceBlockFiles(piece: Int) = Option(new File(dataDir).listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = s"\\.$piece\\.\\d+\\.\\d+\\.blk$$".r.pattern.matcher(name).find()
  })).getOrElse(Array.empty)

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

}