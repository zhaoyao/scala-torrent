package storrent.announce

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import akka.actor.ActorRef
import akka.io.{ UdpConnected, Udp, IO }
import akka.io.Udp.{ Bind, CommandFailed, Bound, Received }
import akka.util.ByteString
import spray.http.Uri
import storrent.{ Slf4jLogging, ActorStack }

import scala.util.Random

object UdpAnnouncer {

  //  val ConnectRequest = 0x00
  //  val ConnectResponse = 0x01
  val AnnounceRequest = 0x02
  val AnnounceResponse = 0x03

  case class ConnectRequest(txId: Int) {
    def encode: ByteString = {
      val b = ByteBuffer.allocate(16)

      b.putLong(0x41727101980l)
      b.putInt(0)
      b.putInt(txId)

      b.flip()
      ByteString(b)
    }
  }

  object ConnectResponse {

    def parse(data: ByteString, expectTxId: Int): Option[ConnectResponse] = {
      if (data.length != 16) {
        None
      } else {
        val b = data.toByteBuffer
        if (b.getInt != 0) {
          return None
        }

        Some(ConnectResponse(b.getInt, b.getLong))
      }
    }
  }

  case class ConnectResponse(txId: Int, connId: Long)

}

class UdpAnnouncer(trackerUri: Uri) extends ActorStack with Slf4jLogging {

  var txId: Int = 0
  val port = 55625

  import context.system
  import UdpAnnouncer._

  IO(UdpConnected) ! UdpConnected.Connect(self,
    new InetSocketAddress(trackerUri.authority.host.address, trackerUri.authority.port),
    Some(new InetSocketAddress("0.0.0.0", port))
  )

  override def wrappedReceive: Receive = init

  def init: Receive = {
    case UdpConnected.Connected =>
      logger.info(s"$trackerUri connected")
      val socket = sender()
      socket ! ConnectRequest(nextTxId)
      context become refreshConnectionId(socket)

    case CommandFailed(_: UdpConnected.Connect) =>
      logger.error(s"Failed to bind udp at port $port")
      context stop self
  }

  def refreshConnectionId(socket: ActorRef): Receive = {
    case Received(data, _) => ConnectResponse.parse(data, txId) match {
      case Some(ConnectResponse(tid, connId)) if tid == this.txId =>
        println(s"Got connection id $connId")
        context become announce(socket, connId)

      case Some(ConnectResponse(tid, connId)) =>
        // retry connect
        println(s"Got connection id $connId $tid")

      case None =>
      // invalid response
    }
  }

  def announce(socket: ActorRef, connId: Long): Receive = {
    case _ =>
  }

  def nextTxId: Int = { txId = new Random().nextInt(); txId }
}
