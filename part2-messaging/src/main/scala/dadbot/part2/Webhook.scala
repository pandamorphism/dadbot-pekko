package dadbot.part2

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/** The chat webhook. Every node serves it: whichever node gets the request, sharding finds the chat's Dad. */
object Webhook:
  final case class Message(from: String, text: String)
  given RootJsonFormat[Message] = jsonFormat2(Message.apply)

  /** `POST /chats/{chatId}/messages`. `tell` is fire-and-forget, so the webhook answers 202 without waiting for Dad. */
  def route(tell: (String, Dad.NewMessage) => Unit): Route =
    path("chats" / Segment / "messages") { chatId =>
      post {
        entity(as[Message]) { message =>
          tell(chatId, Dad.NewMessage(message.from, message.text))
          complete(StatusCodes.Accepted)
        }
      }
    }
