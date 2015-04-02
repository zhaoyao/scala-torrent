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

  def mergeBlocks(pieceIndex: Int): Either[Piece, List[PieceBlock]]

  def mergePieces()

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

  if (!new File(dataDir).exists()) {
    new File(dataDir).mkdirs()
  }

  val pieceFileRegex = "\\.(\\d+)\\.piece$".r
  val blockFileRegex = "\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.blk$".r

  val fileMapping: Map[Int, List[Int]] = {
    // [piece,],[],[]
    val m = mutable.Map[Int, List[Int]]()
    torrent.files.pieces.foreach { piece =>
      piece.locs.foreach { fileLoc =>
        m(fileLoc.fileIndex) = piece.idx :: m.getOrElseUpdate(fileLoc.fileIndex, Nil)
      }
    }
    m.toMap
  }

  /**
   * 返回结果分为以下三种情况
   *
   * piece => blocks
   *
   */
  override def resume: Map[Piece, List[PieceBlock]] = {
    torrent.files.pieces.foreach(p => mergeBlocks(p.idx))
    mergePieces()
    //

    def listDir(dir: File): Array[File] = Option(dir.listFiles()).getOrElse(Array.empty).flatMap { f =>
      println(f)
      if (f.isDirectory) {
        listDir(f)
      } else {
        Array(f)
      }
    }

    //下载完成的文件
    val downloadedFiles = Option(new File(dataDir).listFiles(new FilenameFilter {
      override def accept(dir: File, name: String): Boolean =
        !name.startsWith(".") && !(name.endsWith(".blk") || name.endsWith(".piece"))
    })).getOrElse(Array.empty).flatMap(listDir).map(dropPrefix).toSet

    if (torrent.files.files.map(f => (f.path, f.length)).toSet == downloadedFiles) {
      //完全下载完毕
      torrent.files.pieces.map(p => (p, List(PieceBlock(p.idx, 0, p.length.toInt)))).toMap

    } else {

      val result = mutable.Map[Piece, List[PieceBlock]]().withDefault(_ => Nil)

      //1. 读取 .st 文件 获取已经写入的 piece
      //2. 检测 与 .torrent 文件匹配的文件 获取与该文件重合的piece
      //3. 读取 .blk .piece 文件

      //中间文件
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
  def mergeBlocks(pieceIndex: Int): Either[Piece, List[PieceBlock]] = {
    maybeMergeBlocks(pieceIndex) match {
      case Left(Some(mergedPieceFile)) =>
        val piece = torrent.files.pieces(pieceIndex)
        mergePieceFile(piece, mergedPieceFile)
        Left(piece)

      case Left(None) =>
        Right(Nil)

      case Right(invalidBlockFiles) =>
        // have complete pieces, but not valid
        // remove all blocks and re-request
        logger.debug("Remove invalid piece blocks: {}", invalidBlockFiles)
        invalidBlockFiles.foreach(_.delete())

        //TODO 划分成较小的block
        Right(List(PieceBlock(pieceIndex, 0, torrent.files.pieceLength(pieceIndex))))
    }
  }

  override def mergePieces(): Unit = {

    //TODO 先写入临时文件，待文件确认完整后，去掉后缀名
    pieceFiles.foreach { f =>
      val pieceIndex = f.getName match {
        case pieceFileRegex(pieceIndex) => pieceIndex.toInt
        case _                          => throw new IllegalStateException()
      }

      mergePieceFile(torrent.files.pieces(pieceIndex), f)
    }
  }

  private def mergePieceFile(piece: Piece, pieceFile: File) = {
    val in = new RandomAccessFile(pieceFile, "r")

    try {
      var pos = 0l
      piece.locs.foreach { fileLoc =>

        val tmpF = tmpFile(fileLoc.fileIndex)
        val out = new RandomAccessFile(tmpF, "rw")
        val headerLength = fileMapping(fileLoc.fileIndex).size * 4
        var deleteTmpFile = false

        try {
          // mark piece
          val markPos = fileMapping(fileLoc.fileIndex).indexOf(piece.idx)
          if (markPos < 0) {
            throw new IllegalStateException("File not contains piece ?")
          }
          out.seek(markPos * 4)
          val done = out.readInt() == 1

          if (!done) {
            out.seek(headerLength + fileLoc.offset)
            // copy data
            in.getChannel.transferTo(pos, fileLoc.length, out.getChannel)
            out.seek(markPos * 4)
            out.writeInt(piece.idx)
          }

          // check piece markers
          if (renameTempFileIfCompleted(fileMapping(fileLoc.fileIndex), out, torrent.files.files(fileLoc.fileIndex).path)) {
            tmpF.delete()
          }

        } finally {
          out.close()
        }

        pos += fileLoc.length
      }
    } finally in.close()

    pieceFile.delete()
  }

  def renameTempFileIfCompleted(pieces: List[Int],
                                raf: RandomAccessFile,
                                renameTo: String): Boolean = {
    val headerLength = pieces.size * 4
    raf.seek(0)
    if (pieces.forall(_ => raf.readInt() == 1)) {
      // create real file
      val realFile = new File(dataDir, renameTo)

      // 与临时文件在同一层，无需检查文件夹是否存在了
      val rraf = new RandomAccessFile(realFile, "rw")
      try {
        raf.getChannel.transferTo(headerLength, raf.length() - headerLength, raf.getChannel)
        true
      } finally rraf.close()
    } else {
      false
    }
  }

  def tmpFile(fileIndex: Int): File = {
    val f = new File(dataDir, torrent.files.files(fileIndex).path + ".st")
    if (!f.getParentFile.exists()) {
      f.getParentFile.mkdirs()
    }

    if (!f.exists()) {
      //write piece stat header
      val raf = new RandomAccessFile(f, "rw")
      try {
        fileMapping(fileIndex).foreach(_ => raf.writeInt(-1))
      } finally {
        raf.close()
      }

    } else {
      //maybe partial write?
      val raf = new RandomAccessFile(f, "rw")
      try {
        if (f.length() < fileMapping(fileIndex).length * 4) {
          raf.setLength(0)
          fileMapping(fileIndex).foreach(_ => raf.writeInt(-1))
        }
      } finally {
        raf.close()
      }
    }

    f
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

  private def pieceFiles() = Option(new File(dataDir).listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = pieceFileRegex.pattern.matcher(name).find()
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

  private def dropPrefix(f: File) = {
    val dirLen = if (dataDir.endsWith("/")) dataDir.length else dataDir.length + 1
    (f.getAbsolutePath.substring(dirLen), f.length())
  }

}