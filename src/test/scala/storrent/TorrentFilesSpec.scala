package storrent

import java.nio.file.{ Paths, Files }

import org.scalatest.{ WordSpecLike, WordSpec, FunSuite, Matchers }
import sbencoding.pimpBytes
import storrent.TorrentFiles.{ TorrentFile, FileLoc, Piece }

import scala.io.Source

/**
 * User: zhaoyao
 * Date: 3/12/15
 * Time: 16:24
 */
class TorrentFilesSpec extends WordSpecLike with Matchers {

  "From local files generate single file pieces" should {

    "small piece length" in {

      val targetFile = "src/test/resources/piecesTest/single_files/TestFile.txt"
      val l = Source.fromFile(targetFile).mkString.length

      val files = TorrentFiles.fromLocalFiles(targetFile, pieceLength = 1)

      files.pieces.size shouldBe l
    }

    "large piece length" in {
      val targetFile = "src/test/resources/piecesTest/single_files/TestFile.txt"
      val files = TorrentFiles.fromLocalFiles(targetFile)
      files.pieces.size shouldBe 1
    }
  }

  "From local files generate multi file pieces" should {

    "generate pieces" in {

      val targetFile = "src/test/resources/piecesTest/multi_files"
      val files = TorrentFiles.fromLocalFiles(targetFile, pieceLength = 3)

      files.pieces.size shouldBe 14

      // 跨越文件边界的piece
      files.pieces(6).hash shouldEqual TorrentFiles.pieceHash("0\n1".getBytes, 3)
      files.pieces(13).hash shouldEqual TorrentFiles.pieceHash("\n".getBytes, 1)

    }
  }

  "From metafile" should {

    def validatePieces(torrentFile: String) = {
      val torrent = Torrent(Files.readAllBytes(Paths.get("src/test/resources/torrents/", torrentFile))).get

      val torrentFiles = TorrentFiles.fromMetainfo(torrent)

      torrentFiles.pieces.length shouldEqual torrent.metainfo.info.pieces.length / 20

      torrentFiles.totalLength shouldEqual torrent.metainfo.info.length.orElse {
        torrent.metainfo.info.files.map(fs => fs.map(_.length).sum)
      }.get

      torrentFiles.pieces.flatMap(_.locs.map(_.length)).sum shouldEqual torrentFiles.totalLength

      val pieceLength: Long = torrent.metainfo.info.pieceLength

      var lastLoc: (FileLoc, Int) = null

      torrentFiles.pieces.foreach { piece =>
        piece.locs.foreach { loc =>
          val f = torrentFiles.files(loc.fileIndex)

          if (lastLoc != null) {
            val lastF = torrentFiles.files(lastLoc._1.fileIndex)

            if (loc.fileIndex == lastLoc._1.fileIndex) {
              lastLoc._1.offset + lastLoc._1.length shouldEqual loc.offset
            } else {
              loc.offset shouldEqual 0
              lastLoc._1.offset + lastLoc._1.length shouldEqual lastF.length
            }
          } else {
            loc.offset shouldEqual 0
          }

          lastLoc = (loc, piece.idx)
        }
      }
    }

    "match original files" in {
      val targetTorrents = List(
        "9FE44783704319D9DBAE418F745A1FB106E45B1F.torrent",
        "41F6A92B74EA9744A834AF947860562391C63188.torrent",
        "4732A62A3F529E78750417DB19CBDD9EC374BFD5.torrent",
        "C637A172C655B27C27A03CE681DED7C622E56B6B.torrent",
        "F65CC435888BDDC26C7FD6C8025C5A163FFA4C1D.torrent",
        "ubuntu.torrent"
      )
      targetTorrents.foreach(validatePieces)
    }

  }

  "Locate files" should {

    "return correct files by piece index and position" in {

      val files = TorrentFiles(Nil, List(Piece(0, Array.empty[Byte], List(
        FileLoc(0, 0, 6),
        FileLoc(1, 0, 52),
        FileLoc(2, 0, 10)
      ))), 0)

      files.locateFiles(0, 4, 15) shouldEqual List(FileLoc(0, 4, 6), FileLoc(1, 0, 13))
      files.locateFiles(0, 6, 15) shouldEqual List(FileLoc(1, 0, 15))
      files.locateFiles(0, 4, 60) shouldEqual List(FileLoc(0, 4, 6), FileLoc(1, 0, 52), FileLoc(2, 0, 6))

    }

  }

}
