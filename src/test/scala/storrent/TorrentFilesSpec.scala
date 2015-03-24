package storrent

import java.nio.file.{ Paths, Files }

import org.scalatest.{ WordSpecLike, WordSpec, FunSuite, Matchers }
import sbencoding.pimpBytes

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
      val bc = Files.readAllBytes(Paths.get("src/test/resources/piecesTest/", torrentFile)).parseBencoding

      val torrent = Torrent(Files.readAllBytes(Paths.get("src/test/resources/piecesTest/", torrentFile))).get

      val torrentFiles = TorrentFiles.fromMetainfo(torrent)

      torrentFiles.pieces.length shouldEqual torrent.metainfo.info.pieces.length / 20

      torrentFiles.totalLength shouldEqual torrent.metainfo.info.length.orElse {
        torrent.metainfo.info.files.map(fs => fs.map(_.length).sum)
      }.get

      torrentFiles.pieces.flatMap(_.locs.map(_.length)).sum shouldEqual torrentFiles.totalLength
    }

    "match original files" in {
      val targetTorrents = List("287B2203D248270F79B34B0F819C47FF0B9D9D28.torrent")
      targetTorrents.foreach(validatePieces)
    }

  }

}
