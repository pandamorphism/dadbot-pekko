package dadbot.part1

import dadbot.common.{PunGenerator, RecordingChat, Script}
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

class NaiveHandlerSpec extends AnyWordSpec:
  given ExecutionContext = ExecutionContext.global

  "The naive webhook handler" should {
    "tell the same joke twice when two messages for one chat overlap" in {
      val chat = RecordingChat()
      val handler = NaiveHandler(JokeDb(), PunGenerator(thinking = 200.millis), chat)

      handler.onMessage(Script.engineering, Script.sebastian)
      handler.onMessage(Script.engineering, Script.mattia)

      val replies = Await.result(chat.awaitReplies(2), 5.seconds)
      assert(replies.map(_.text).distinct.size == 1)
    }

    "not repeat itself when messages never overlap" in {
      val chat = RecordingChat()
      val handler = NaiveHandler(JokeDb(), PunGenerator(thinking = 10.millis), chat)

      Await.result(handler.onMessage(Script.engineering, Script.sebastian), 5.seconds)
      Await.result(handler.onMessage(Script.engineering, Script.mattia), 5.seconds)

      assert(chat.all.map(_.text).distinct.size == 2)
    }
  }
