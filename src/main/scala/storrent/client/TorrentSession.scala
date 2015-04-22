package storrent.client

import java.nio.file.Paths

import akka.actor.{Kill, Props}
import storrent.TorrentFiles.{Piece, PieceBlock}
import storrent._
import storrent.pwp.Message._
import storrent.pwp.{Message, PeerListener, PwpPeer}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Random, Success}

object TorrentSession {

  def props(metainfo: Torrent, storeUri: String, blockSize: Int = FixedBlockSize) =
    Props(classOf[TorrentSession], metainfo, storeUri, blockSize)

  case class PeerState(have: mutable.BitSet = mutable.BitSet.empty,
                       choked: Boolean = false,
                       interested: Boolean = false /*, stats*/)

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

    def withCompletedBlock(blockIndex: Int): ActivePiece = {
      blockRequestCount(blockIndex) = -1; this
    }
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

  val hostPeer = context.actorOf(PwpPeer.props(metainfo, 0, self))

  var peerStates = mutable.Map[Peer, PeerState]().withDefault(p => PeerState())

  val missingBlocks = mutable.Map[Int, List[PieceBlock]]().withDefault(p => Nil)
  val completedPieces = mutable.Set.empty[Int]

  val pendingBlocks = mutable.LinkedHashSet[PieceBlock]()

  val totalPieces = metainfo.metainfo.info.pieces.length
  val bootstrappingPieceCount = Math.min(DefaultBootstrappingPieceCount, totalPieces)

  val interestedMarks = mutable.Set[Peer]()

  //1. which piece/block is requesting from which peer

  //2. what request-pair is requested but not respond(*)

  val allPieces = metainfo.files.pieces.toSet
  val activePieces = mutable.Map[Int, ActivePiece]()

  val peerRequests = mutable.Map[Peer, ArrayBuffer[(Int, Int, Long)]]().withDefault(_ => ArrayBuffer.empty)

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
      val current = System.currentTimeMillis()
      for ((peer, requests) <- peerRequests) {
        val timeoutRequests = requests.filter(_._3 + RequestTimeout.toMillis < current)

        for (
          (pieceIndex, blkIndex, _) <- requests.filter(_._3 + RequestTimeout.toMillis < current) if activePieces.contains(pieceIndex)
        ) {
          val c = activePieces(pieceIndex).blockRequestCount(blkIndex)
          if (c > 0) {
            activePieces(pieceIndex).blockRequestCount(blkIndex) = c - 1
          }
        }
        requests --= timeoutRequests
      }

      peerRequests.retain((_, r) => r.nonEmpty)

      if (peerRequests.isEmpty) {
        for ((p, _) <- new Random().shuffle(peerStates.filterNot(x => x._2.choked).toList).headOption) {
          schedulePieceRequests2(p)
        }
      }

    case DumpStats =>
      logger.info(s"Peers: ${peerStates.size}")
      checkProgress()
  }

  def cancelBlock(f: (PieceBlock => Boolean)) = {
    //    inflightBlocks.filter(p => f(p._1)).foreach(p => {
    //      val (blk, requests) = p
    //      requests.foreach(pair => {
    //        sendPeerMsg(pair._1, Cancel(blk.piece, blk.offset, blk.length))
    //      })
    //    })
    //
    //    inflightBlocks.retain { (blk, _) => !f(blk) }
  }

  def handleMessage(peer: Peer, state: PeerState, msg: Message): Unit = msg match {
    case Keepalive =>
      schedulePieceRequests2(peer)

    case Choke =>
      logger.info(s"$peer is choking us")
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
      schedulePieceRequests2(peer)

    case Have(piece) =>
      state.have += piece
      peerStates += (peer -> state)

      ifInterested(peer, Set(piece))
      schedulePieceRequests2(peer)

    case Message.Piece(pieceIndex, offset, data) =>
      peerRequests(peer) --= peerRequests(peer).filter(t => t._1 == pieceIndex && t._2 == offset / blockSize)

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

      schedulePieceRequests2(peer)

    case Request(piece, offset, length) =>
      store.readPiece(piece, offset, length) match {
        case Success(Some(data)) =>
          sendPeerMsg(peer, Message.Piece(piece, offset, data))
        case Success(None) =>
        // request non-exist piece block

        case Failure(e) =>
          //read failure
          logger.info("read block failed", e)
          hostPeer ! ((peer, Kill))
      }

    case x: Cancel =>
      //TODO: handle cancel request
      logger.info("Cancel request: {}", x)

  }

  def ifInterested(peer: Peer, pieces: Set[Int]) = {
    if ((activePieces.filterNot(_._2.isCompleted).keySet & pieces).nonEmpty && !interestedMarks.contains(peer)) {
      sendPeerMsg(peer, Interested)
      interestedMarks += peer
    } else {
      sendPeerMsg(peer, Uninterested)
      interestedMarks += peer
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

    val healthy = peerStates.values.flatMap(_.have).toSet.size.toFloat / allPieces.size

    logger.info("Total blocks: %d, missing: %d, completed: %d, %.2f%%. Healthy: %.2f%%".format(
      totalBlocks,
      missing,
      totalBlocks - missing,
      ((totalBlocks - missing).toFloat / totalBlocks) * 100,
      healthy * 100
    ))

    logger.info(s"Current requests $peerRequests")
  }

  def schedulePieceRequests2(peer: Peer): Unit = {
    def choosePiece(): Option[Int] = {
      val n = allPieces.size
      val start = new Random().nextInt(n)
      checkRange(start, n).orElse(checkRange(0, start))
    }

    def checkRange(start: Int, end: Int): Option[Int] = {
      val state = peerStates(peer)
      val end2 = Math.min(end, Math.min(allPieces.size - 1, state.have.max))
      (start until end2).find(p => !completedPieces(p) && state.have(p) && activePieces.contains(p))
    }

    chooseBlock2(peer) match {
      case Some((pieceIndex, blkIndex)) =>
        requestBlock(peer, pieceIndex, blkIndex)

      case None =>
        choosePiece() match {
          case Some(pieceIndex2) =>
            val blkIndex2 = activePieces(pieceIndex2).blockRequestCount.zipWithIndex.sortBy(_._1).head._2
            requestBlock(peer, pieceIndex2, blkIndex2)

          case None =>
            sendPeerMsg(peer, Uninterested)
        }
    }


  }

  def requestBlock(peer: Peer, pieceIndex: Int, blockIndex: Int) = {
    activePieces(pieceIndex).blockRequestCount(blockIndex) += 1
    peerRequests(peer) = peerRequests(peer) += ((pieceIndex, blockIndex, System.currentTimeMillis()))

    val blockLength = metainfo.files.pieces(pieceIndex).blockLength(blockIndex, blockSize)
    sendPeerMsg(peer, Request(pieceIndex, blockIndex * blockSize, blockLength))
//    logger.info(s"Requesting block $peer => ${Request(pieceIndex, blockIndex * blockSize, blockLength)}")
  }

  def chooseBlock2(peer: Peer): Option[(Int, Int)] = {
    for ((pieceIndex, ap) <- activePieces if peerStates(peer).have(pieceIndex)) {
      for ((dl, blkIndex) <- ap.blockRequestCount.zipWithIndex if dl == 0) {
        return Some((pieceIndex, blkIndex))
      }
    }
    None
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
    activePieces ++= (allPieces.map(_.idx) -- completedPieces -- activePieces.keySet)
      .map(p => (p, ActivePiece(metainfo.files.pieces(p), blockSize))) // 未下载过的

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
    interestedMarks -= peer

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
