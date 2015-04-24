package storrent.client

import java.io.{ RandomAccessFile, File }

import org.scalatest.{ Inside, BeforeAndAfter, Matchers, WordSpec }
import storrent.TorrentFiles.PieceBlock
import storrent.pwp.Message.Piece
import storrent.{ TorrentFiles, Util }

import scala.util.{ Failure, Success }

/**
 * User: zhaoyao
 * Date: 4/7/15
 * Time: 10:50
 */
class LocalFilesystemSpec extends WordSpec with Matchers with BeforeAndAfter {

  var dataDir: File = null

  before {
    dataDir = File.createTempFile("temp", java.lang.Long.toString(System.nanoTime()))
    dataDir.delete()
    dataDir.mkdir()
  }

  after {
    def walk(file: File)(f: File => Unit): Unit = if (file.isDirectory) {
      Option(file.listFiles()).getOrElse(Array.empty[File]).foreach(walk(_)(f))
      f(file)
    } else f(file)

    walk(dataDir)(_.delete())
  }

  "basic operation" should {

    "read whole piece" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, TorrentSession.FixedBlockSize)

      torrentFiles.pieces.foreach { p =>
        fs.readPiece(p.idx, 0, p.length) match {
          case Success(Some(data)) => Util.sha1(data) shouldEqual p.hash
          case x                   => fail(x.toString)
        }
      }
    }

    "read half piece" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, TorrentSession.FixedBlockSize)

      torrentFiles.pieces.foreach { p =>

        (fs.readPiece(p.idx, 0, p.length / 2), fs.readPiece(p.idx, p.length / 2, p.length / 2), fs.readPiece(p.idx, 0, p.length)) match {
          case (Success(Some(p1)), Success(Some(p2)), Success(Some(w))) =>
            p1 ++ p2 shouldEqual w
          case _ => fail()
        }

      }
    }

    "read overflow" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, TorrentSession.FixedBlockSize)

      fs.readPiece(0, 0, torrentFiles.pieces.head.length + 1) match {
        case Failure(e: IllegalArgumentException) => //pass
        case _                                    => fail("IllegalArgumentException expected")
      }
    }

    "write block" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, TorrentSession.FixedBlockSize)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString, TorrentSession.FixedBlockSize)

      fs.readPiece(0, 0, torrentFiles.pieceLength(0) - 2) match {
        case Success(Some(d)) =>
          fs2.writePiece(0, 0, d) shouldBe Success(true)
        case _ => fail()
      }
    }

    "merge blocks" in {
      val path = "src/test/resources/filesystem/simple"
      val blockSize = 1

      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, blockSize)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString, blockSize)

      fs.readPiece(0, 0, 4) match {
        case Success(Some(d)) =>
          fs2.writePiece(0, 0, d.take(1)) shouldBe Success(true)
          new File(dataDir, s".0.0.1.blk").exists() shouldBe true
          fs2.mergeBlocks(0) shouldEqual Left(None)

          fs2.writePiece(0, 1, d.take(2).drop(1)) shouldBe Success(true)
          fs2.writePiece(0, 2, d.take(3).drop(2)) shouldBe Success(true)
          fs2.writePiece(0, 3, d.take(4).drop(3)) shouldBe Success(true)

          new File(dataDir, s".0.1.1.blk").exists() shouldBe true
          new File(dataDir, s".0.2.1.blk").exists() shouldBe true
          new File(dataDir, s".0.3.1.blk").exists() shouldBe true
          Inside.inside(fs2.mergeBlocks(0)) {
            case Left(Some(TorrentFiles.Piece(0, _, _))) =>
          }

          new File(dataDir, s".0.0.1.blk").exists() shouldBe false
          new File(dataDir, s".0.1.1.blk").exists() shouldBe false
          new File(dataDir, s".0.2.1.blk").exists() shouldBe false
          new File(dataDir, s".0.3.1.blk").exists() shouldBe false
          new File(dataDir, s".0.piece").exists() shouldBe true

        case _ => fail()
      }
    }

    "remove broken blocks when merging" in {
      val path = "src/test/resources/filesystem/simple"
      val blockSize = 1
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, blockSize)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString, blockSize)

      fs.readPiece(0, 0, 4) match {
        case Success(Some(d)) =>
          fs2.writePiece(0, 0, d.take(1)) shouldBe Success(true)
          fs2.writePiece(0, 1, d.take(2).drop(1)) shouldBe Success(true)
          fs2.writePiece(0, 2, d.take(3).drop(2)) shouldBe Success(true)
          fs2.writePiece(0, 3, Array((d(3) + 1).toByte)) shouldBe Success(true)

          new File(dataDir, s".0.0.1.blk").exists() shouldBe true
          new File(dataDir, s".0.1.1.blk").exists() shouldBe true
          new File(dataDir, s".0.2.1.blk").exists() shouldBe true
          new File(dataDir, s".0.3.1.blk").exists() shouldBe true

          fs2.mergeBlocks(0) shouldEqual Right(List(
            PieceBlock(0, 0, 0, blockSize),
            PieceBlock(0, 1, 1, blockSize),
            PieceBlock(0, 2, 2, blockSize),
            PieceBlock(0, 3, 3, blockSize)
          ))
          new File(dataDir, s".0.0.1.blk").exists() shouldBe false
          new File(dataDir, s".0.1.1.blk").exists() shouldBe false
          new File(dataDir, s".0.2.1.blk").exists() shouldBe false
          new File(dataDir, s".0.3.1.blk").exists() shouldBe false
          new File(dataDir, s".0.piece").exists() shouldBe false

        case _ => fail()
      }
    }

    "merge piece file" in {
      val path = "src/test/resources/filesystem/simple"
      val blockSize = 1
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, blockSize)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString, blockSize)

      fs.readPiece(0, 0, 4) match {
        case Success(Some(d)) =>
          fs2.writePiece(0, 0, d.take(1)) shouldBe Success(true)
          fs2.writePiece(0, 1, d.take(2).drop(1)) shouldBe Success(true)
          fs2.writePiece(0, 2, d.take(3).drop(2)) shouldBe Success(true)
          fs2.writePiece(0, 3, d.take(4).drop(3)) shouldBe Success(true)

          new File(dataDir, s".0.0.1.blk").exists() shouldBe true
          new File(dataDir, s".0.1.1.blk").exists() shouldBe true
          new File(dataDir, s".0.2.1.blk").exists() shouldBe true
          new File(dataDir, s".0.3.1.blk").exists() shouldBe true

          fs2.mergeBlocks(0)
          new File(dataDir, s".0.piece").exists() shouldBe true

          fs2.mergePieces()
          new File(dataDir, s".0.piece").exists() shouldBe false

          //校验各个分散文件中的数据hash
          Util.sha1(torrentFiles.pieces.head.locs.foldLeft(Array.empty[Byte]) { (arr, loc) =>
            // 要么在 xx.st里
            val f = torrentFiles.files(loc.fileIndex)
            if (new File(dataDir, s"${f.path}.st").exists()) {
              val headerLength = torrentFiles.fileMapping(loc.fileIndex).size * 4

              val raf = new RandomAccessFile(new File(dataDir, s"${f.path}.st"), "r")
              raf.seek(torrentFiles.fileMapping(loc.fileIndex).indexOf(0) * 4)
              raf.readInt() shouldEqual 1
              raf.close()

              arr ++ Util.readFile(new File(dataDir, s"${f.path}.st"), headerLength + loc.offset, loc.length)
            } else if (new File(dataDir, f.path).exists()) {
              arr ++ Util.readFile(new File(dataDir, f.path), loc.offset, loc.length)
            } else {
              fail(f.path + " not found")
            }
          }) shouldEqual torrentFiles.pieces.head.hash

        case _ => fail()
      }
    }
  }

  "resume" should {

    "resume from complete blk files" in { // .blk => .piece => .st => finial file
      val path = "src/test/resources/filesystem/simple"
      val blockSize = 1
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path, blockSize)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString, blockSize)

      torrentFiles.pieces foreach { piece =>
        fs.readPiece(piece.idx, 0, piece.length) match {
          case Success(Some(d)) => d.zipWithIndex.foreach(b => fs2.writePiece(piece.idx, b._2, Array(b._1)) shouldEqual Success(true))
          case _                => fail()
        }
      }

      val ret = fs2.resume()
      ret.size shouldEqual torrentFiles.pieces.size
      ret.foreach { p =>
        val piece = torrentFiles.pieces(p._1)
        p._2 shouldEqual piece.blocks(blockSize).toSet
      }
    }
  }

}
