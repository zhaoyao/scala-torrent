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

  logger.info(s"Starting actor ${getClass.getName}")

  override def receive: Receive = {
    case x =>
      MDC.put("akkaSource", myPath)
      super.receive(x)
  }
}
