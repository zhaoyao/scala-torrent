package storrent.client

import java.io.{ RandomAccessFile, File }

import org.scalatest.{ BeforeAndAfter, Matchers, WordSpec }
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
      val fs = new LocalFilesystem(torrentFiles, path)

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
      val fs = new LocalFilesystem(torrentFiles, path)

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
      val fs = new LocalFilesystem(torrentFiles, path)

      fs.readPiece(0, 0, torrentFiles.pieces.head.length + 1) match {
        case Failure(e: IllegalArgumentException) => //pass
        case _                                    => fail("IllegalArgumentException expected")
      }
    }

    "write block" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString)

      fs.readPiece(0, 0, torrentFiles.pieceLength(0) - 2) match {
        case Success(Some(d)) =>
          fs2.writePiece(0, 0, d) shouldBe Success(true)
        case _ => fail()
      }
    }

    "merge blocks" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString)

      (fs.readPiece(0, 0, torrentFiles.pieceLength(0) - 2), fs.readPiece(0, torrentFiles.pieceLength(0) - 2, 2)) match {
        case (Success(Some(d1)), Success(Some(d2))) =>
          fs2.writePiece(0, 0, d1) shouldBe Success(true)
          new File(dataDir, s".0.0.${d1.length}.blk").exists() shouldBe true
          fs2.mergeBlocks(0) shouldEqual Right(List(PieceBlock(0, torrentFiles.pieceLength(0) - 2, 2)))

          fs2.writePiece(0, d1.length, d2) shouldBe Success(true)
          new File(dataDir, s".0.${d1.length}.2.blk").exists() shouldBe true
          fs2.mergeBlocks(0) shouldEqual Left(torrentFiles.pieces.head)

          new File(dataDir, s".0.0.${d1.length}.blk").exists() shouldBe false
          new File(dataDir, s".0.${d1.length}.2.blk").exists() shouldBe false
          new File(dataDir, s".0.piece").exists() shouldBe true

        case _ => fail()
      }
    }

    "remove broken blocks when merging" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString)

      (fs.readPiece(0, 0, torrentFiles.pieceLength(0) - 2), fs.readPiece(0, torrentFiles.pieceLength(0) - 2, 2)) match {
        case (Success(Some(d1)), Success(Some(d2))) =>
          fs2.writePiece(0, 0, d1) shouldBe Success(true)
          new File(dataDir, s".0.0.${d1.length}.blk").exists() shouldBe true
          fs2.mergeBlocks(0) shouldEqual Right(List(PieceBlock(0, torrentFiles.pieceLength(0) - 2, 2)))

          fs2.writePiece(0, d1.length, Array(-1, -1)) shouldBe Success(true)
          //re-download whole piece
          fs2.mergeBlocks(0) shouldEqual Right(List(PieceBlock(0, 0, torrentFiles.pieceLength(0))))
          new File(dataDir, s".0.0.${d1.length}.blk").exists() shouldBe false
          new File(dataDir, s".0.${d1.length}.2.blk").exists() shouldBe false
          new File(dataDir, s".0.piece").exists() shouldBe false
        case _ => fail()
      }
    }

    "merge piece file" in {
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString)

      (fs.readPiece(0, 0, torrentFiles.pieceLength(0) - 2), fs.readPiece(0, torrentFiles.pieceLength(0) - 2, 2)) match {
        case (Success(Some(d1)), Success(Some(d2))) =>
          fs2.writePiece(0, 0, d1) shouldBe Success(true)
          new File(dataDir, s".0.0.${d1.length}.blk").exists() shouldBe true
          fs2.mergeBlocks(0) shouldEqual Right(List(PieceBlock(0, torrentFiles.pieceLength(0) - 2, 2)))

          fs2.writePiece(0, d1.length, d2) shouldBe Success(true)
          new File(dataDir, s".0.${d1.length}.2.blk").exists() shouldBe true
          fs2.mergeBlocks(0) shouldEqual Left(torrentFiles.pieces.head)

          new File(dataDir, s".0.0.${d1.length}.blk").exists() shouldBe false
          new File(dataDir, s".0.${d1.length}.2.blk").exists() shouldBe false
          new File(dataDir, s".0.piece").exists() shouldBe true

          fs2.mergePieces()
          new File(dataDir, s".0.piece").exists() shouldBe false

          //校验各个分散文件中的数据hash
          Util.sha1(torrentFiles.pieces(0).locs.foldLeft(Array.empty[Byte]) { (arr, loc) =>
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
          }) shouldEqual torrentFiles.pieces(0).hash

        case _ => fail()
      }
    }
  }

  "resume" should {

    "resume from complete blk files" in { // .blk => .piece => .st => finial file
      val path = "src/test/resources/filesystem/simple"
      val torrentFiles = TorrentFiles.fromLocalFiles(path, pieceLength = 4)
      val fs = new LocalFilesystem(torrentFiles, path)
      val fs2 = new LocalFilesystem(torrentFiles, dataDir.toString)

      torrentFiles.pieces foreach { piece =>
        fs.readPiece(piece.idx, 0, piece.length) match {
          case Success(Some(d)) => fs2.writePiece(piece.idx, 0, d) shouldEqual Success(true)
          case _                => fail()
        }
      }

      val ret = fs2.resume()
      ret.size shouldEqual torrentFiles.pieces.size
      ret.foreach { p =>
        p._2.size shouldEqual 1
        val piece = torrentFiles.pieces(p._1)
        p._2.head shouldEqual PieceBlock(piece.idx, 0, piece.length)
      }
    }
  }

}
