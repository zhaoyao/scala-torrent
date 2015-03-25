package storrent.client

import java.nio.file.Paths
import java.util

import akka.actor.{Actor, ActorLogging}
import storrent.TorrentFiles.Piece
import storrent.store.LocalFileStore
import storrent.{Peer, Torrent, TorrentFiles}

import scala.collection.mutable

object TorrentSession {

  case class PeerState(have: mutable.BitSet,
                       choked: Boolean,
                       unchoked: Boolean
                       /*, stats*/)

  case class PeerHasPieces(peer: Peer, pieces: Set[Int])

  case class PeerPieceRequest(piece: Int, offset: Int, length: Int)

  case class PeerChoked(peer: Peer)

  case class PeerUnchoked(peer: Peer)


}


class TorrentSession(metainfo: Torrent,
                     downloadDir: String) extends Actor with ActorLogging {

  import TorrentSession._

  val files = TorrentFiles.fromMetainfo(metainfo)
  val fs = new LocalFileStore(Paths.get(downloadDir, metainfo.metainfo.info.name).toString)

  val peerStates = mutable.Map[Peer, PeerState]()

  val completedPieces = mutable.Set(resume(): _*)
  log.info("Resumed pieces: {}", completedPieces)

  /**
   * completedPieces ++ incompletePieces shouldEqual files.pieces
   */
  val incompletePieces = mutable.Set[Piece]()

  /**
   * 等待下载的piece
   */
  var pendingPieces = mutable.Set(files.pieces: _*) -- completedPieces

  /**
   * 下载中的piece
   */
  val inflightPieces = mutable.Set[Piece]()

  override def preStart(): Unit = {
    log.info("Starting TorrentClient[{}]", metainfo.infoHash)
  }

  override def receive: Receive = {
    case PeerHasPieces(peer, pieces) =>
      val state = peerStates.getOrElseUpdate(peer, PeerState(mutable.BitSet.empty))
      pieces.foreach(state.have += _)

    //
  }

  /**
   * 根据pendingPieces & activePieces，选取下一步下载的pieces到pendingPieces中
   * 在peer状态发生变化，或者各个piece集合发生变化时，应该调用该方法，选取下一步待下载的piece
   */
  def schedulePieceRequests(): Unit = {

  }

  /**
   * 为某个piece选取目标peer.
   * 需要考虑几个因素:
   *  1. peer是否拥有该piece
   *  2. 自己是否被对方choke
   *  3. peer的历史下载速度如何
   */
  def peerForPiece(piece: Piece): Option[Peer] = {
    peerStates.find { p =>
      val (_, state) = p
      state.have(piece.idx)
    }.map(_._1)
  }

  /**
   * 读取本地piece暂存文件，与piece hash进行对比，返回已经下载成功的piece
   */
  def resume() = {
    fs.list(_.path.split("/").last.matches("\\.\\d+\\.piece")).filter(f => {
      val pieceIndex = Paths.get(f.path).toFile.getName.split("\\.")(1).toInt
      val piece = files.pieces(pieceIndex)
      val pieceLength = piece.locs.map(_.length).sum
      val fileLength = f.length
      pieceLength == fileLength && util.Arrays.equals(fs.checksum(f), piece.hash)
    }).map(_.path.split("/").last.split("\\.")(1).toInt).map(files.pieces).toList
  }
}
