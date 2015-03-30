package storrent.client

import java.nio.file.Paths
import java.util

import akka.actor.{Actor, ActorLogging, Props}
import storrent.TorrentFiles.Piece
import storrent.pwp.Message._
import storrent.pwp.{Message, PeerListener, PwpPeer}
import storrent.store.LocalFileStore
import storrent.{Peer, Torrent, TorrentFiles}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object TorrentSession {

  def props(metainfo: Torrent, downloadDir: String) = Props(classOf[TorrentSession], metainfo, downloadDir)

  case class PeerState(have: mutable.BitSet = mutable.BitSet.empty,
                       choked: Boolean = true,
                       interested: Boolean = false
                       /*, stats*/)

  case class FoundPeers(peers: List[Peer])

  case class InboundPeer(peer: Peer)

}

/**
  * TODO: 重新阅读pwp 协议/ utp 协议 / 以及dht 协议
  *
  * TorrentSession是运行torrent的中心逻辑，它通过PwpPeer/DhtPeer/UtpPeer与外部peer通讯/获取数据
  */
class TorrentSession(metainfo: Torrent,
                     downloadDir: String)
  extends Actor with ActorLogging with PeerListener {

  import TorrentSession._

  val files = TorrentFiles.fromMetainfo(metainfo)
  val fs = new LocalFileStore(Paths.get(downloadDir, metainfo.metainfo.info.name).toString)

  val hostPeer = context.actorOf(PwpPeer.props(metainfo, 7778))

  var peerStates = Map[Peer, PeerState]().withDefault(p => PeerState())

  val completedPieces = mutable.Set(resume(): _*)
  log.info("Resumed pieces: {}", completedPieces)

  /**
   * completedPieces ++ incompletePieces shouldEqual files.pieces
   */
  val incompletePieces = mutable.Set(files.pieces: _*) -- completedPieces

  /**
   * 等待下载的piece
   */
  var pendingPieces = mutable.Set[Piece]()

  /**
   * 下载中的piece
   */
  val inflightPieces = mutable.Set[Piece]()

  override def preStart(): Unit = {
    log.info("Starting TorrentSession[{}]", metainfo.infoHash)
  }

  override def receive: Receive = {
    case (peer: Peer, msg: Message) =>
      //      log.info("Got message from[{}]: {}", peer, msg)
      handleMessage(peer, peerStates(peer), msg)
  }

  def handleMessage(peer: Peer, state: PeerState, msg: Message): Unit = msg match {
    case Keepalive =>
      schedulePieceRequests()

    case Choke =>
      peerStates += (peer -> state.copy(choked = true))

    case Unchoke =>
      peerStates += (peer -> state.copy(choked = false))
      log.info("Unchoke peer {} => {}", peer, peerStates(peer).choked)

    case Interested =>
      peerStates += (peer -> state.copy(interested = true))

    case Uninterested =>
      peerStates += (peer -> state.copy(interested = false))

    case Bitfield(pieces) =>
      state.have ++= pieces
      peerStates += (peer -> state)
      schedulePieceRequests()

    case Have(piece) =>
      state.have += piece
      peerStates += (peer -> state)
      schedulePieceRequests()

    case Message.Piece(piece, offset, data) =>
      writePiece(piece, offset, data)
    // onSuccess => peers.foreach(_ ! Have(piece))

    case Request(piece, offset, length) =>
    //read file
    // sender ! Piece()


  }

  def writePiece(pieceIndex: Int, blockOffset: Int, blockData: Array[Byte]): Unit = {
    log.info("Got piece {}, {}, {}", pieceIndex, blockOffset, blockData.length)
    if (blockData.length == files.pieceLength(pieceIndex)) {
    }


    //validate piece
    //update incompletePieces & completedPieces
    //schedulePieceRequests

  }

  /**
   * 根据pendingPieces & activePieces，选取下一步下载的pieces到pendingPieces中
   * 在peer状态发生变化，或者各个piece集合发生变化时，应该调用该方法，选取下一步待下载的piece
   */
  def schedulePieceRequests(): Unit = {
    //    log.info("Schedule requests, states={}", peerStates)
    if (pendingPieces.nonEmpty) {
      if (!flushPieceRequest()) {
        //之前schedule的piece无法正常下载，或被对端choke. 重新选择piece
        pendingPieces.clear()
      }
    }

    val waitingPieces = incompletePieces
      .filterNot(inflightPieces.contains(_))
      .filter { p =>
      peerStates.values.find(_.have.contains(p.idx)).isDefined
    }

    for (p <- new Random().shuffle(waitingPieces).headOption) {
      log.info("Scheduled piece: {}", p)
      pendingPieces += p
    }

    flushPieceRequest()
  }

  def flushPieceRequest(): Boolean = {
    val fired = new ArrayBuffer[Piece]()
    for (piece <- pendingPieces) {
      for (peer <- peerForPiece(piece)) {
        hostPeer ! Tuple2(peer, Request(piece.idx, 0, piece.length.toInt))
        fired += piece
      }
    }

    pendingPieces --= fired
    fired.nonEmpty
  }

  /**
   * 为某个piece选取目标peer.
   * 需要考虑几个因素:
   *  1. peer是否拥有该piece
   *  2. 自己是否被对方choke
   *  3. peer的历史下载速度如何 [*TODO]
   */
  def peerForPiece(piece: Piece): Option[Peer] = {
    peerStates.find { p =>
      val (_, state) = p
      !state.choked && state.have(piece.idx)
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

  override def onPeerAdded(peer: Peer): Unit = ()

  override def onPeerRemoved(peer: Peer): Unit = {
    log.info("Removing peer: {}", peer)
    peerStates -= peer
    // removing requests
  }
}
