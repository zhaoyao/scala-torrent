package storrent

import org.scalatest.{ FunSuite, Matchers }

import scala.io.Source

/**
 * User: zhaoyao
 * Date: 3/12/15
 * Time: 16:24
 */
class TorrentFilesTest extends FunSuite with Matchers {

  test("Calc single file pieces") {
    val targetFile = "src/test/resources/piecesTest/single_files/TestFile.txt"
    val l = Source.fromFile(targetFile).mkString.length

    val files = TorrentFiles.fromLocalFiles(targetFile, pieceLength = 1)

    files.pieces.size shouldBe l

  }

  test("Calc single file pieces large pieceSize") {
    val targetFile = "src/test/resources/piecesTest/single_files/TestFile.txt"
    val files = TorrentFiles.fromLocalFiles(targetFile)
    files.pieces.size shouldBe 1
  }

  test("Calc multi files pieces") {
    val targetFile = "src/test/resources/piecesTest/multi_files"
    val files = TorrentFiles.fromLocalFiles(targetFile, pieceLength = 3)

    files.pieces.size shouldBe 14

    // 跨越文件边界的piece
    files.pieces(6).hash shouldEqual TorrentFiles.pieceHash("0\n1".getBytes, 3)
    files.pieces(13).hash shouldEqual TorrentFiles.pieceHash("\n".getBytes, 1)
  }

}
