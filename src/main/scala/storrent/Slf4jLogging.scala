package storrent

import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC

/**
 * User: zhaoyao
 * Date: 4/1/15
 * Time: 14:37
 */
trait Slf4jLogging extends ActorStack with StrictLogging {

  private[this] val myPath = self.path.toString

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = logger.info("postStop")

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = logger.info("preStart")

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    logger.info(s"preRestart: ${reason.getMessage} $message")
  }

  @throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    logger.info(s"preRestart: ${reason.getMessage}")
  }

  override def receive: Receive = {
    case x =>
      MDC.put("akkaSource", myPath)
      super.receive(x)
  }
}
