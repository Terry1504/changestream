package changestream.actors

import akka.util.Timeout
import spray.httpx.SprayJsonSupport._
import spray.routing._
import akka.actor._
import ch.qos.logback.classic.Level
import changestream.{ChangeStream, ChangeStreamEventListener, ChangeStreamEventDeserializer}
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger
import spray.routing.HttpService
import spray.json.DefaultJsonProtocol

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class ControlInterfaceActor extends Actor with ControlInterface {
  def actorRefFactory = context
  def receive = runRoute(controlRoutes)
}

trait ControlInterface extends HttpService with DefaultJsonProtocol {
  import ControlActor._

  protected val log = LoggerFactory.getLogger(getClass)

  implicit val memoryInfoFormat = jsonFormat3(MemoryInfo)
  implicit val statusFormat = jsonFormat7(Status)
  implicit val successFormat = jsonFormat1(Success)
  implicit val errorFormat = jsonFormat1(Error)
  implicit val logLevelFormat = jsonFormat1(LogLevel)

  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(10 seconds)

  def controlRoutes: Route = {
    get {
      pathSingleSlash {
        detach() {
          complete(getStatus)
        }
      } ~
        path("status") {
          detach() {
            complete(getStatus)
          }
        }
    } ~
      post {
        logLevelRoutes ~
        pauseRoute ~
        resetRoute ~
        resumeRoute
      }
  }

  def logLevelRoutes: Route = path("log_level") {

    entity(as[LogLevel]) { logLevel =>
      complete {
        try {
          logLevel.level.toLowerCase match {
            case "all" | "trace" | "debug" | "info" | "warn" | "error" | "off" =>
              val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
              rootLogger.setLevel(Level.toLevel(logLevel.level))
              Success(s"ChangeStream logging level has been set to ${logLevel.level}.")
            case _ =>
              log.error(s"ControlActor received invalid log level ${logLevel.level}.")
              Error(s"Invalid log level: ${logLevel.level}")
          }
        }
        catch {
          case e: Exception =>
            log.error("Caught an exception trying to set log level.", e)
            Error(s"ChangeStream has encountered an error: ${e.getMessage}")
        }
      }
    }
  }

  def pauseRoute: Route = path("pause") {
    complete {
      try {
        log.info("Received pause request, pausing...")
        ChangeStream.disconnect() match {
          case true =>
            log.info("Paused.")
            Success("ChangeStream is Paused. `/resume` to pick up where you left off. `/reset` to discard past events and resume in real time.")
          case false =>
            log.warn("Pause failed, perhaps we are already paused?")
            Error("ChangeStream is not connected. `/resume` or `/reset` to connect.")
        }
      }
      catch {
        case e: Exception =>
          log.error("Caught an exception trying to pause.", e)
          Error(s"ChangeStream has encountered an error: ${e.getMessage}")
      }
    }
  }

  def resetRoute: Route = path("reset") {
    complete {
      try {
        log.info("Received reset request, resetting the binlog position...")
        ChangeStream.reset() match {
          case true =>
            Success("ChangeStream has been reset, and will begin streaming events in real time when resumed.")
          case false =>
            log.warn("Reset failed, perhaps we are not paused?")
            Error("You must pause ChangeStream before resetting.")
        }
      }
      catch {
        case e: Exception =>
          log.error("Caught an exception trying to reset.", e)
          Error(s"ChangeStream has encountered an error: ${e.getMessage}")
      }
    }
  }

  def resumeRoute: Route = path("resume") {
    complete {
      try {
        log.info("Received resume request, resuming event processing...")
        ChangeStream.connect() match {
          case true =>
            Success("ChangeStream is now connected.")
          case false =>
            log.warn("Resume failed, perhaps we are not paused?")
            Error("ChangeStream is already connected.")
        }
      }
      catch {
        case e: Exception =>
          log.error("Caught an exception trying to resume.", e)
          Error(s"ChangeStream has encountered an error: ${e.getMessage}")
      }
    }
  }

  def getStatus = {
    val storedPosition = Await.result(ChangeStreamEventListener.getStoredPosition, 60 seconds)

    Status(
      server = ChangeStream.serverName,
      clientId = ChangeStream.clientId,
      isConnected = ChangeStream.isConnected,
      binlogClientPosition = ChangeStreamEventListener.getCurrentPosition,
      lastStoredPosition = storedPosition.getOrElse(""),
      binlogClientSequenceNumber = ChangeStreamEventDeserializer.getCurrentSequenceNumber,
      memoryInfo = MemoryInfo(
        Runtime.getRuntime().totalMemory(),
        Runtime.getRuntime().maxMemory(),
        Runtime.getRuntime().freeMemory()
      )
    )
  }
}

object ControlActor {
  case class Status(
                     server: String,
                     clientId: Long,
                     isConnected: Boolean,
                     binlogClientPosition: String,
                     lastStoredPosition: String,
                     binlogClientSequenceNumber: Long,
                     memoryInfo: MemoryInfo
                   )

  case class MemoryInfo(heapSize: Long, maxHeap: Long, freeHeap: Long)

  case class LogLevel(level: String)

  case class Success(success: String)
  case class Error(error: String)
}
