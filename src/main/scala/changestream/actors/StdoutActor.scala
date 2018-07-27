package changestream.actors

import akka.actor.{Actor, ActorRef, ActorRefFactory}
import changestream.actors.PositionSaver.EmitterResult
import changestream.events.MutationWithInfo
import com.typesafe.config.{Config, ConfigFactory}

class StdoutActor(getNextHop: ActorRefFactory => ActorRef,
                  config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  protected val nextHop = getNextHop(context)

  def receive = {
    case MutationWithInfo(_, pos, _, _, Some(message: String)) =>
      println(message)
      nextHop ! EmitterResult(pos)
  }
}
