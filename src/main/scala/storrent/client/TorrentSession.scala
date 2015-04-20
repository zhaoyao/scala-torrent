package storrent.client

import java.nio.file.Paths

import akka.actor.{ Kill, Props }
import storrent.TorrentFiles.{ Piece, PieceBlock }
import storrent._
import storrent.pwp.Message._
import storrent.pwp.{ Message, PeerListener, PwpPeer }

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Random, Success }

object TorrentSession {

  def props(metainfo: Torrent, storeUri: String, blockSize: Int = FixedBlockSize) =
    Props(classOf[TorrentSession], metainfo, storeUri, blockSize)

  case class PeerState(have: mutable.BitSet = mutable.BitSet.empty,
                       choked: Boolean = false,
                       interested: Boolean = false /*, stats*/ )

  val FixedBlockSize = 16 * 1024

  private case object CheckRequest

  //  private case object DumpPeers
  private case object DumpStats

  val RequestTimeout = 10.seconds

  val DefaultBootstrappingPieceCount = 4

  object ActivePiece {

    def apply(piece: Piece, blockSize: Int): ActivePiece = {
      ActivePiece(piece, blockSize, Array.fill(piece.numBlocks(blockSize))(0))
    }

  }

  case class ActivePiece(piece: Piece, blockSize: Int, blockRequestCount: Array[Int]) {
    def completedBlocks = blockRequestCount.filter(_ == -1).zipWithIndex.map(_._2)

    def missingBlocks = (0 until piece.numBlocks(blockSize)).toSet -- completedBlocks

    def requestedBlocks = blockRequestCount.filter(_ != 0)

    def isCompleted = completedBlocks.length == piece.numBlocks(blockSize)

    def withCompletedBlock(blockIndex: Int): ActivePiece = { blockRequestCount(blockIndex) = -1; this }
  }

}

class TorrentSession(metainfo: Torrent,
                     storeUri: String,
                     blockSize: Int)
    extends ActorStack with Slf4jLogging with PeerListener {

  import TorrentSession._
  import context.dispatcher

  val files = TorrentFiles.fromMetainfo(metainfo)
  val store = TorrentStore(metainfo, Paths.get(storeUri, metainfo.infoHash).toString, blockSize)

  val hostPeer = context.actorOf(PwpPeer.props(metainfo, 7778, self))

  var peerStates = mutable.Map[Peer, PeerState]().withDefault(p => PeerState())

  val missingBlocks = mutable.Map[Int, List[PieceBlock]]().withDefault(p => Nil)
  val completedPieces = mutable.Set.empty[Int]

  val pendingBlocks = mutable.LinkedHashSet[PieceBlock]()

  val totalPieces = metainfo.metainfo.info.pieces.length
  val bootstrappingPieceCount = Math.min(DefaultBootstrappingPieceCount, totalPieces)

  //1. which piece/block is requesting from which peer

  //2. what request-pair is requested but not respond(*)

  val allPieces = metainfo.files.pieces.toSet
  val activePieces = mutable.Map[Int, ActivePiece]()

  val peerRequests = mutable.Map[Peer, ArrayBuffer[(Int, Int, Long)]]().withDefaultValue(ArrayBuffer.empty)

  /**
   * block -> (peer, issuedAt)
   */
  val inflightBlocks = mutable.Map[PieceBlock, mutable.Set[(Peer, Long)]]()

  override def preStart(): Unit = {
    logger.info(s"Starting TorrentSession[${metainfo.infoHash}], pieces: ${metainfo.files.pieces.size}, pieceLength: ${metainfo.metainfo.info.pieceLength}")
    resume()

    context.system.scheduler.schedule(10.seconds, 10.seconds, self, DumpStats)
    context.system.scheduler.schedule(10.seconds, 10.seconds, self, CheckRequest)
  }

  override def wrappedReceive: Receive = {
    case (peer: Peer, msg: Message) =>
      //      log.info("Got message from[{}]: {}", peer, msg)
      handleMessage(peer, peerStates(peer), msg)

    case CheckRequest =>
      inflightBlocks.foreach {
        case (blk, peers) =>
          peers.retain(_._2 + RequestTimeout.toMillis < System.currentTimeMillis())
      }

    case DumpStats =>
      logger.info(s"Peers: ${peerStates.size}")
      checkProgress()
  }

  def cancelBlock(f: (PieceBlock => Boolean)) = {
    inflightBlocks.filter(p => f(p._1)).foreach(p => {
      val (blk, requests) = p
      requests.foreach(pair => {
        sendPeerMsg(pair._1, Cancel(blk.piece, blk.offset, blk.length))
      })
    })

    inflightBlocks.retain { (blk, _) => !f(blk) }
  }

  def handleMessage(peer: Peer, state: PeerState, msg: Message): Unit = msg match {
    case Keepalive =>
      schedulePieceRequests(peer)

    case Choke =>
      peerStates += (peer -> state.copy(choked = true))

    case Unchoke =>
      peerStates += (peer -> state.copy(choked = false))

    case Interested =>
      peerStates += (peer -> state.copy(interested = true))

    case Uninterested =>
      peerStates += (peer -> state.copy(interested = false))

    case Bitfield(pieces) =>
      state.have ++= pieces
      peerStates += (peer -> state)

      ifInterested(peer, pieces)
      schedulePieceRequests(peer)

    case Have(piece) =>
      state.have += piece
      peerStates += (peer -> state)

      ifInterested(peer, Set(piece))
      schedulePieceRequests(peer)

    case Message.Piece(pieceIndex, offset, data) =>
      writePiece(pieceIndex, offset, data) match {
        case (true, Some(piece)) =>
          // cancel blocks belongs to this piece
          cancelBlock(_.piece == pieceIndex)
          peerStates.foreach(p => sendPeerMsg(p._1, Have(pieceIndex)))
        //TODO uninterested

        case (true, None) =>
          cancelBlock(blk => blk.piece == pieceIndex && blk.offset == offset)

        case (false, _) =>
        //          cancelBlock(_.piece == pieceIndex)
        //wait for other peers response
      }

      schedulePieceRequests(peer)

    case Request(piece, offset, length) =>
      store.readPiece(piece, offset, length) match {
        case Success(Some(data)) =>
          sendPeerMsg(peer, Message.Piece(piece, offset, data))
        case Success(None) =>
        // request non-exist piece block

        case Failure(e) =>
          //read failure
          hostPeer ! ((peer, Kill))
      }

    case x: Cancel =>
      //TODO: handle cancel request
      logger.info("Cancel request: {}", x)

  }

  def ifInterested(peer: Peer, pieces: Set[Int]) = {
    if ((missingBlocks.keySet & pieces).nonEmpty) {
      logger.debug("Peer interested. {}", peer)
      sendPeerMsg(peer, Interested)
    }
  }

  /**
   * 储存block
   *
   * (true, _) => 下载并写入成功
   * (false, blocksNeedToBeDownloaded) => 写入失败
   */
  def writePiece(pieceIndex: Int, blockOffset: Int, blockData: Array[Byte]): (Boolean, Option[Piece]) = {
    logger.info(s"Got piece $pieceIndex, $blockOffset, ${blockData.length}")
    if (completedPieces.contains(pieceIndex)) {
      logger.debug("Skip already merged block")
      return (true, None)
    }

    require(blockOffset % blockSize == 0)
    require(blockData.length == metainfo.files.pieces(pieceIndex).blockLength(blockOffset / blockSize, blockSize))

    store.writePiece(pieceIndex, blockOffset, blockData) match {
      case Success(true) =>
        val ap = activePieces(pieceIndex)
        ap.blockRequestCount(blockOffset / blockSize) = -1
        if (ap.blockRequestCount(blockOffset / blockSize) > 0) {
          //cancel blocks
        }
        checkProgress()

        if (ap.isCompleted) {
          store.mergeBlocks(pieceIndex) match {
            case Left(piece) =>
              assert(ap.isCompleted)
              activePieces -= pieceIndex
              completedPieces += pieceIndex

              //TODO 根据已完成的piece，得到下载完全的文件，没有文件下完前不需要merge
              store.mergePieces()
              (true, Some(piece))

            case Right(leftBlocks) =>
              //            missingBlocks(pieceIndex) ++= leftBlocks
              //invalid piece found, re-download all blocks
              val ap = activePieces(pieceIndex)
              //TODO cancel requests
              leftBlocks.foreach(blk => ap.requestedBlocks(blk.index) = 0)
              (true, None)
          }
        } else {
          (true, None)
        }

      case Success(false) =>
        logger.debug("Skip already exists block")
        (true, None)

      case Failure(e) =>
        logger.warn(s"Failed to write piece: ${e.getMessage}")
        (false, None)
    }

  }

  private def checkProgress() = {

    val totalBlocks: Int = metainfo.files.pieces.foldLeft(0) { (sum, p) =>
      sum + p.numBlocks(blockSize)
    }
    val missing: Int = activePieces.values.foldLeft(0) { (sum, ap) =>
      sum + ap.blockRequestCount.count(_ != -1)
    }
    logger.info("Total blocks: %d, missing: %d, completed: %d, %.2f%%".format(
      totalBlocks,
      missing,
      totalBlocks - missing,
      ((totalBlocks - missing).toFloat / totalBlocks) * 100
    ))
  }

  def schedulePieceRequests(peer: Peer): Unit = {
    for ((pieceIndex, blockIndex) <- chooseBlock(peer)) {
      //      val ap = activePieces(pieceIndex)
      activePieces(pieceIndex).blockRequestCount(blockIndex) += 1
      peerRequests(peer) += ((pieceIndex, blockIndex, System.currentTimeMillis()))

      val blockLength = metainfo.files.pieces(pieceIndex).blockLength(blockIndex, blockSize)
      sendPeerMsg(peer, Request(pieceIndex, blockIndex * blockSize, blockLength))
      logger.debug(s"Requesting block => ${Request(pieceIndex, blockIndex * blockSize, blockLength)}")
    }
  }

  var _bootstrappingPieces: List[Int] = Nil

  private def bootstrappingPieces = {
    if (_bootstrappingPieces.isEmpty) {

      if (peerStates.size < 10) {
        //TODO 如果现有成功下载的block，直接选用，不等待更多peer连接
      } else {

        //从已经下载的piece中选择
        val piecesWithCompletedBlock = activePieces.values
          .filter(_.completedBlocks.nonEmpty).toList
          .sortBy(-_.completedBlocks.length)
          .take(bootstrappingPieceCount)
          .map(_.piece.idx)

        _bootstrappingPieces =
          if (piecesWithCompletedBlock.size != bootstrappingPieceCount) {
            //数量不够 按照各节点拥有个数选出前4个piece
            piecesWithCompletedBlock ++ peerStates
              .map(_._2).flatMap(_.have)
              .filterNot(piecesWithCompletedBlock.contains)
              .foldLeft(Map.empty[Int, Int].withDefault(_ => 0)) { (m, piece) => m + (piece -> (m(piece) + 1)) }.toList
              .sortBy(-_._2)
              .map(_._1)
              .take(bootstrappingPieceCount - piecesWithCompletedBlock.size)
          } else {
            piecesWithCompletedBlock
          }

        logger.debug(s"Bootstrapping pieces ${_bootstrappingPieces}")
      }
    }
    _bootstrappingPieces
  }

  def chooseBlock(peer: Peer): Option[(Int, Int)] = {
    val pendingPiece: Option[Int] = if (completedPieces.size <= bootstrappingPieceCount) {
      //pick a piece randomly
      new Random().shuffle(bootstrappingPieces).find(peerStates(peer).have).headOption
    } else {
      peerStates
        .map(_._2).flatMap(_.have)
        .foldLeft(Map.empty[Int, Int].withDefault(_ => 0)) { (m, piece) =>
          m + (piece -> (m(piece) + 1))
        }
        .toList.sortBy(-_._2).map(_._1)
        .find(peerStates(peer).have.contains)
    }
    //Total blocks: 3194, missing: 3127, completed: 67, 2.10%
    // if completedPieces.size >= 4  select rarest pieces
    // if requestedBlocks == totalBlocks end game
    pendingPiece flatMap { p =>
      //find block
      activePieces(p).blockRequestCount.zipWithIndex
        .find(_._1 == 0)
        //TODO end game
        /*.orElse(missingBlocks.find(_ != -1))  */
        .map(b => (p, b._2))
    }

  }

  /**
   * 读取本地piece暂存文件，与piece hash进行对比，返回已经下载成功的piece
   */
  def resume() = {

    val pieces = store.resume().map(p => {
      val (pieceIndex, blocks) = p
      blocks.foldLeft(ActivePiece(metainfo.files.pieces(pieceIndex), blockSize)) { (ap, blk) =>
        ap.withCompletedBlock(blk.index)
      }
    })

    completedPieces ++= pieces.filter(_.isCompleted).map(_.piece.idx)

    activePieces ++= pieces.filterNot(_.isCompleted).map(ap => (ap.piece.idx, ap)).toMap // 已下载过的
    activePieces ++= (allPieces.map(_.idx) -- activePieces.keySet).map(p => (p, ActivePiece(metainfo.files.pieces(p), blockSize))) // 未下载过的

    checkProgress()
  }

  def sendPeerMsg(target: Peer, msg: Message) = {
    //    logger.debug(s"Sending message $msg to $target")
    hostPeer ! ((target, msg))
  }

  override def onPeerAdded(peer: Peer): Unit = {
    if (completedPieces.nonEmpty) {
      sendPeerMsg(peer, Bitfield(completedPieces.toSet))
    }
    sendPeerMsg(peer, Unchoke)
  }

  override def onPeerRemoved(peer: Peer): Unit = {
    logger.debug("Removing peer: {}", peer)
    peerStates -= peer
    inflightBlocks.values.foreach(_.retain(_._1 != peer))
    inflightBlocks.retain((_, peers) => peers.nonEmpty)

    for (requests <- peerRequests.remove(peer)) {
      for (req <- requests) {
        val (pieceIndex, blockIndex, _) = req
        val count = activePieces(pieceIndex).blockRequestCount(blockIndex)
        if (count > 0) {
          activePieces(pieceIndex).blockRequestCount(blockIndex) -= 1
        }
      }
    }
  }
}
