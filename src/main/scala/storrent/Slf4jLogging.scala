package storrent

import org.slf4j.{ LoggerFactory, MDC }

/**
 * User: zhaoyao
 * Date: 4/1/15
 * Time: 14:37
 */
trait Slf4jLogging extends ActorStack {

  val logger = LoggerFactory.getLogger(getClass)
  private[this] val myPath = self.path.toString

  logger.info("Starting actor " + getClass.getName)

  override def receive: Receive = {
    case x =>
      MDC.put("akkaSource", myPath)
      super.receive(x)
  }
}
