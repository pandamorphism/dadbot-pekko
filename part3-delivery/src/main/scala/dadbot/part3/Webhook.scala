package dadbot.part3

import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import scala.concurrent.Future
import scala.util.{Failure, Success}
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/** The chat webhook. Unlike part 2, it only answers once Dad has saved the message. */
object Webhook:
  final case class Message(from: String, text: String)
  given RootJsonFormat[Message] = jsonFormat2(Message.apply)

  /** `POST /chats/{chatId}/messages`: 202 once the message is in the journal, 503 if Dad didn't confirm that in time,
    * so the chat sends it again. A 503 doesn't mean it wasn't saved: sharding buffers the message and may still deliver
    * it, so the retry can save it twice. That's at-least-once delivery, and part 4's problem.
    */
  def route(tell: (String, Message) => Future[Done]): Route =
    path("chats" / Segment / "messages") { chatId =>
      post {
        entity(as[Message]) { message =>
          onComplete(tell(chatId, message)) {
            case Success(_) => complete(StatusCodes.Accepted)
            case Failure(_) => complete(StatusCodes.ServiceUnavailable)
          }
        }
      }
    }
