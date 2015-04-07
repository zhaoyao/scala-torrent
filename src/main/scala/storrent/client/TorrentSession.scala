package storrent.client

import java.nio.file.Paths

import akka.actor.{ Kill, Actor, Props }
import storrent.TorrentFiles.Piece
import storrent.pwp.Message._
import storrent.pwp.{ Message, PeerListener, PwpPeer }
import storrent._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object TorrentSession {

  def props(metainfo: Torrent, storeUri: String) = Props(classOf[TorrentSession], metainfo, storeUri)

  case class PeerState(have: mutable.BitSet = mutable.BitSet.empty,
                       choked: Boolean = false,
                       interested: Boolean = false /*, stats*/ )

  private case object CheckRequest

  val RequestTimeout = 5.minutes
}

/**
 * TODO: 重新阅读pwp 协议/ utp 协议 / 以及dht 协议
 *
 * TorrentSession是运行torrent的中心逻辑，它通过PwpPeer/DhtPeer/UtpPeer与外部peer通讯/获取数据
 */
class TorrentSession(metainfo: Torrent,
                     storeUri: String)
    extends ActorStack with Slf4jLogging with PeerListener {

  import TorrentSession._

  val files = TorrentFiles.fromMetainfo(metainfo)
  val store = TorrentStore(metainfo, Paths.get(storeUri, metainfo.infoHash).toString)

  val hostPeer = context.actorOf(PwpPeer.props(metainfo, 7778, self))

  var peerStates = mutable.Map[Peer, PeerState]().withDefault(p => PeerState())

  val missingBlocks = mutable.Map[Int, List[PieceBlock]]().withDefault(p => Nil)
  val completedPieces = mutable.Set[Int]()

  val pendingBlocks = mutable.LinkedHashSet[PieceBlock]()

  /**
   * block -> (peer, issuedAt)
   */
  var activeBlocks = mutable.Map[PieceBlock, mutable.Set[(Peer, Long)]]()

  override def preStart(): Unit = {
    logger.info("Starting TorrentSession[{}]", metainfo.infoHash)
    resume()
  }

  override def wrappedReceive: Receive = {
    case (peer: Peer, msg: Message) =>
      //      log.info("Got message from[{}]: {}", peer, msg)
      handleMessage(peer, peerStates(peer), msg)

    case CheckRequest =>
      activeBlocks.foreach {
        case (blk, peers) =>
          peers.retain(_._2 + RequestTimeout.toMillis < System.currentTimeMillis())
      }
  }

  def cancelBlock(f: (PieceBlock => Boolean)) = {
    activeBlocks.filter(p => f(p._1)).foreach(p => {
      val (blk, requests) = p
      requests.foreach(pair => {
        sendPeerMsg(pair._1, Cancel(blk.piece, blk.offset, blk.length))
      })
    })

    activeBlocks.retain { (blk, _) => !f(blk) }
  }

  def handleMessage(peer: Peer, state: PeerState, msg: Message): Unit = msg match {
    case Keepalive =>
      schedulePieceRequests()

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
      schedulePieceRequests()

    case Have(piece) =>
      state.have += piece
      peerStates += (peer -> state)

      ifInterested(peer, Set(piece))
      schedulePieceRequests()

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
          cancelBlock(_.piece == pieceIndex)
        //wait for other peers response
      }

      schedulePieceRequests()

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
    logger.info("Got piece {}, {}, {}", Array(pieceIndex, blockOffset, blockData.length))
    if (completedPieces.contains(pieceIndex)) {
      logger.debug("Skip already merged block")
      return (true, None)
    }

    store.writePiece(pieceIndex, blockOffset, blockData) match {
      case Failure(_) => return (false, None)
      case Success(true) =>
        store.mergeBlocks(pieceIndex) match {
          case Left(piece) =>
            // piece completed
            completedPieces += pieceIndex
            missingBlocks -= pieceIndex
            checkProgress()
            (true, Some(piece))

          case Right(leftBlocks) =>
            missingBlocks(pieceIndex) ++= leftBlocks
            checkProgress()
            (true, None)
        }
      case Success(false) =>
        logger.debug("Skip already exists block")
        (true, None)
    }

  }

  private def checkProgress() = {
    logger.info("Total pieces: %d, missing: %d, completed: %d, %.2f%%".format(
      metainfo.files.pieces.size, missingBlocks.size, completedPieces.size,
      (completedPieces.size.toFloat / metainfo.files.pieces.size) * 100
    ))
  }

  /**
   * 根据pendingPieces & activePieces，选取下一步下载的pieces到pendingPieces中
   * 在peer状态发生变化，或者各个piece集合发生变化时，应该调用该方法，选取下一步待下载的piece
   */
  def schedulePieceRequests(): Unit = {
    if (pendingBlocks.nonEmpty) {
      flushPieceRequest()
      return
    }

    if (completedPieces.size == metainfo.files.pieces.size) {
      logger.info("Torrent completed")
      //TODO merge pieces to original file/dir structure
      //TODO stop scheudle for requests
      //TODO set state to finished && start seeding
      store.mergePieces()
      return
    }

    val pieceCounts = peerStates.toList.map(_._2).flatMap(_.have).foldLeft(Map.empty[Int, Int].withDefault(_ => 0)) { (m, piece) =>
      m + (piece -> (m(piece) + 1))
    }

    pendingBlocks ++= pieceCounts.toList.sortBy(_._2).foldLeft(List[PieceBlock]()) { (xs, p) =>
      val (pieceIndex, _) = p
      xs ++ missingBlocks.getOrElse(pieceIndex, Nil).filterNot(b => activeBlocks.contains(b) && activeBlocks(b).nonEmpty)
    }.take(5)

    flushPieceRequest()
  }

  def flushPieceRequest(): Boolean = {
    logger.debug("Pending blocks: {}", pendingBlocks)

    val fired = new ArrayBuffer[PieceBlock]()
    for (block <- pendingBlocks) {
      for (peer <- peerForPiece(block.piece)) {
        sendPeerMsg(peer, Request(block.piece, block.offset, block.length))

        activeBlocks.getOrElseUpdate(PieceBlock(block.piece, block.offset, block.length), mutable.Set.empty)
          .add((peer, System.currentTimeMillis()))

        fired += block
      }
    }

    pendingBlocks --= fired
    fired.nonEmpty
  }

  /**
   * 为某个piece选取目标peer.
   * 需要考虑几个因素:
   *  1. peer是否拥有该piece
   *  2. 自己是否被对方choke
   *  3. peer的历史下载速度如何 [*TODO]
   */
  def peerForPiece(pieceIndex: Int): List[Peer] = {
    peerStates.filter { p =>
      val (_, state) = p
      !state.choked && state.have(pieceIndex)
    }.map(_._1).toList
  }

  /**
   * 读取本地piece暂存文件，与piece hash进行对比，返回已经下载成功的piece
   */
  def resume() = {

    val resumed: Map[Int, List[PieceBlock]] = store.resume()
    resumed.foreach { p =>
      val (pieceIndex, blocks) = p
      val piece = metainfo.files.pieces(pieceIndex)

      var lastBlock: PieceBlock = null
      val sortedBlocks = blocks.sortBy(_.offset)

      sortedBlocks.sortBy(_.offset).foreach { blk =>
        if (lastBlock == null) {
          //first element
          if (blk.offset != 0) {
            missingBlocks(pieceIndex) ++= List(PieceBlock(blk.piece, 0, blk.offset))
          }
        } else {

          if (lastBlock.offset + lastBlock.length != blk.offset) {
            missingBlocks(pieceIndex) ++= List(PieceBlock(blk.piece, lastBlock.offset + lastBlock.length, blk.offset - lastBlock.offset + lastBlock.length))
          }
        }

        lastBlock = blk
      }

      if (sortedBlocks.nonEmpty) {
        val last: PieceBlock = sortedBlocks.last
        if (last.offset + last.length != piece.length) {
          missingBlocks(pieceIndex) ++= List(PieceBlock(last.piece, last.offset + last.length, (piece.length - last.offset + last.length).toInt))
        }
      }

      if (missingBlocks.isEmpty)
        completedPieces += piece.idx
    }

    metainfo.files.pieces.filter(p => !missingBlocks.contains(p.idx) && !completedPieces.contains(p.idx)).foreach { p =>
      missingBlocks(p.idx) ++= List(PieceBlock(p.idx, 0, p.length.toInt))
    }

    logger.info("Resumed blocks => {}", resumed)

    checkProgress()
  }

  def sendPeerMsg(target: Peer, msg: Message) = {
    hostPeer ! ((target, msg))
  }

  override def onPeerAdded(peer: Peer): Unit = {
    sendPeerMsg(peer, Bitfield(completedPieces.toSet))
    sendPeerMsg(peer, Unchoke)
  }

  override def onPeerRemoved(peer: Peer): Unit = {
    logger.info("Removing peer: {}", peer)
    peerStates -= peer
    // removing requests
  }
}
