package storrent.dht

import java.lang
import java.net.InetSocketAddress

import akka.io.UdpConnected.CommandFailed

import scala.concurrent.duration.DurationInt

import akka.actor.{ Stash, ActorRef, ActorSystem, Props }
import akka.io.{ IO, UdpConnected }
import akka.pattern._
import akka.util.{ Timeout, ByteString }
import sbencoding.{ pimpAny, pimpBytes }
import storrent.Util.decodeHex
import storrent._
import storrent.dht.KRPC._

import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

object DHTPeerConnection extends App {

  implicit val QueryTimeout: Timeout = 120.seconds

  case class Tx(id: Array[Byte], at: Long, q: String,
                promise: Promise[DHTResponse])

  val system = ActorSystem()

  val conn = system.actorOf(Props(classOf[DHTPeerConnection], PeerId(), Peer("", "router.bittorrent.com", 6881)))

  import akka.pattern._
  import system.dispatcher

  //  (conn ? dht.GetPeers("a41d1f89286454b18d8d4cb2e02ffe11587476c4")).mapTo[DHTResponse] onComplete {
  //    case Success(TryNodes(nodes)) => println("TryNodes: " + nodes)
  //    case Success(Peers(values))   => println("Peers: " + values)
  //    case Failure(e)               => e.printStackTrace()
  //  }

  (conn ? dht.Announce(50511, "a41d1f89286454b18d8d4cb2e02ffe11587476c4")).mapTo[Announced.type] onComplete {
    case Success(Announced) => println("Announce success")
    case Failure(e)         => e.printStackTrace()
  }
}

/**
 * TODO: 消息重发
 */
class DHTPeerConnection(peerId: BigInt,
                        peer: Peer) extends ActorStack with Slf4jLogging with Stash {

  import DHTPeerConnection._
  import KRPC.KrpcProtocol._
  import context.{ dispatcher, system }

  val commander = context.parent

  val remote = new InetSocketAddress(peer.ip, peer.port)
  IO(UdpConnected) ! UdpConnected.Connect(self, remote)

  val txs = mutable.Map[String, Tx]()

  var _txId = Array[Byte](0, 0)

  var peerToken: Option[String] = null

  def nextTxId(): Array[Byte] = {
    val n = _txId(0) | (_txId(1) << 1)
    val n2 = (n + 1) % 0xff
    Array((n2 & 0x0f).toByte, ((n2 >> 1) & 0x0f).toByte)
  }

  override def wrappedReceive: Receive = init

  def init: Receive = {
    case UdpConnected.Connected =>
      logger.info(s"DHT Peer $peer connected")
      //      context.system.scheduler.schedule(0.seconds, 5.seconds, self, Ping)
      commander ! true
      context.become(ready(sender()))
      unstashAll()

    case CommandFailed(_: UdpConnected.Connected) =>
      commander ! false
      context stop self

    case _ => stash()
  }

  def ready(connection: ActorRef): Receive = {
    case UdpConnected.Received(data) =>
      handleMessage(connection, data.asByteBuffer.parseBencoding.convertTo[Message])

    case dht.Ping =>
      pipe(sendQuery(connection, KRPC.Ping(peerId))).to(sender())

    case dht.GetPeers(ih) =>
      pipe(sendQuery(connection, KRPC.GetPeers(peerId, decodeHex(ih)))).to(sender())

    case a @ dht.Announce(port, ih) =>
      val rawInfoHash = decodeHex(ih)
      val f = if (peerToken == null) {
        sendQuery(connection, KRPC.GetPeers(peerId, rawInfoHash)).mapTo[DHTResponse].flatMap {
          case x => self ? a
        }
      } else {
        peerToken match {
          case Some(token) =>
            sendQuery(connection, KRPC.AnnouncePeer(peerId, rawInfoHash, port, token))
          case None =>
            Future.failed(new DHTException(202, "Peer not support announce"))
        }
      }

      pipe(f).to(sender())

    case UdpConnected.Disconnected => context.stop(self)
  }

  def handleMessage(connection: ActorRef, msg: KRPC.Message): Unit = {
    msg
  } match {
    case Response(t, r) =>
      val tid = strTxId(t)
      if (txs.contains(tid)) {
        val tx = txs(tid)
        val resp = tx.q match {
          case Query.Ping         => r.convertTo[PingResponse]
          case Query.FindNode     => r.convertTo[FindNodeResponse]
          case Query.GetPeers     => r.convertTo[GetPeersResponse]
          case Query.AnnouncePeer => r.convertTo[AnnouncePeerResponse]
        }
        handleResponse(tx, resp)
        txs -= tid

      } else {
        logger.info(s"Drop un-exists or expired response: $tid")
      }

    case Query(t, q, a) =>
      handleQuery(connection, t, a)

    case Error(t, err) =>
      val tid = strTxId(t)
      if (txs.contains(tid)) {
        val tx = txs(tid)
        tx.promise.tryFailure(new DHTException(err._1, err._2))
        txs -= tid
      } else {
        logger.info(s"Drop un-exists or expired response: ${strTxId(t)}")
      }
  }

  def handleQuery(conn: ActorRef, tx: Array[Byte], q: QueryMessage) = q match {
    case KRPC.Ping(_)                                => sendResponse(conn, tx, PingResponse(peerId))
    case KRPC.FindNode(_, target)                    =>

    case KRPC.GetPeers(_, infoHash)                  =>

    case KRPC.AnnouncePeer(_, infoHash, port, token) =>
  }

  def handleResponse(tx: Tx, r: ResponseMessage) = {
    r match {
      case GetPeersResponse(_, token, _, _) =>
        peerToken = token
      case _ =>
    }

    tx.promise.trySuccess(r match {
      case _: PingResponse                            => Pong(System.currentTimeMillis() - tx.at)
      case FindNodeResponse(_, nodes)                 => Nodes(nodes)
      case GetPeersResponse(_, _, Some(values), None) => Peers(values)
      case GetPeersResponse(_, _, None, Some(nodes))  => TryNodes(nodes)
      case AnnouncePeerResponse(_)                    => Announced
    })
  }

  def sendQuery(conn: ActorRef, q: QueryMessage, txId: Option[Array[Byte]] = None) = {
    val tid = txId.getOrElse(nextTxId())
    val p = Promise[DHTResponse]()
    txs += (strTxId(tid) -> Tx(tid, System.currentTimeMillis(), q.q, p))

    val data = ByteString(Query(tid, q.q, q).toBencoding.toByteArray())
    logger.info(s"Sending ${strTxId(tid)} ${new String(data.toArray)}")
    conn ! UdpConnected.Send(data)
    p.future
  }

  def sendResponse(conn: ActorRef, txId: Array[Byte], r: ResponseMessage): Unit = {
    val data = ByteString(Response(txId, r.toBencoding).toBencoding.toByteArray())
    conn ! UdpConnected.Send(data)
  }

  private def strTxId(t: Array[Byte]) = s"0x${lang.Integer.toString(t(1), 16)}${lang.Integer.toString(t(0), 16)}"
}
