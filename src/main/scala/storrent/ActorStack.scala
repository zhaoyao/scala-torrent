package storrent

import akka.actor.Actor

/**
 * User: zhaoyao
 * Date: 4/1/15
 * Time: 14:24
 */
trait ActorStack extends Actor {

  def wrappedReceive: Receive

  override def receive: Actor.Receive = {
    case x => if (wrappedReceive.isDefinedAt(x)) wrappedReceive(x) else unhandled(x)
  }
}
