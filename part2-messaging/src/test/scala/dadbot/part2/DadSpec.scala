package dadbot.part2

import com.typesafe.config.ConfigFactory
import dadbot.common.{ChatMessage, PunGenerator, RecordingChat}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

/** A pun generator the test answers by hand, so it decides how long Dad is thinking. */
final class ScriptedPuns extends PunGenerator:
  final case class Request(messages: Seq[ChatMessage], told: Set[String], joke: Promise[String])
  private val requests = LinkedBlockingQueue[Request]()

  def generate(messages: Seq[ChatMessage], told: Set[String]): Future[String] =
    val request = Request(messages, told, Promise())
    requests.put(request)
    request.joke.future

  def next(): Request = Option(requests.poll(5, TimeUnit.SECONDS)).getOrElse(throw AssertionError("Dad never asked"))
  def pending: Boolean = !requests.isEmpty

class DadSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("""
          pekko.remote.artery.canonical.port = 0
          pekko.cluster.seed-nodes = []
        """)
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike:

  private val quiet = 500.millis
  private val sebastian = ChatMessage("Sebastian", "Why is this test flaky?")
  private val mattia = ChatMessage("Mattia", "It's flaky for me too")
  private val tim = ChatMessage("Tim", "Is CI down?")

  private def newDad(chatId: String) =
    val puns = ScriptedPuns()
    val chat = RecordingChat()
    val dad = spawn(Dad(chatId, puns, chat, quiet))
    def say(m: ChatMessage): Unit = dad ! Dad.NewMessage(m.from, m.text)
    (puns, chat, say)

  "A batching Dad" should {
    "tell one joke about everything said before the chat went quiet" in {
      val (puns, chat, say) = newDad("#batch")
      say(sebastian)
      say(mattia)

      val request = puns.next()
      assert(request.messages == Seq(sebastian, mattia))
      request.joke.success("joke 1")
      assert(Await.result(chat.awaitReplies(1), 5.seconds).map(_.text) == Vector("joke 1"))
    }

    "start waiting for quiet again after every message" in {
      val (puns, _, say) = newDad("#chatty")
      // Each gap is shorter than the quiet period, together they are longer.
      say(sebastian)
      Thread.sleep(300)
      say(mattia)
      Thread.sleep(300)
      say(tim)

      assert(puns.next().messages == Seq(sebastian, mattia, tim))
    }

    "save what is said while he is thinking for the next joke, and never repeat himself" in {
      val (puns, chat, say) = newDad("#busy")
      say(sebastian)
      val first = puns.next()

      say(mattia)
      say(tim)
      Thread.sleep(quiet.toMillis * 2)
      assert(!puns.pending, "Dad must not start a second joke while he is still thinking")

      first.joke.success("joke 1")
      val second = puns.next()
      assert(second.messages == Seq(mattia, tim))
      assert(second.told == Set("joke 1"))
      second.joke.success("joke 2")
      assert(Await.result(chat.awaitReplies(2), 5.seconds).map(_.text) == Vector("joke 1", "joke 2"))
    }

    "move on to the next batch when a pun fails" in {
      val (puns, chat, say) = newDad("#flaky")
      say(sebastian)
      val first = puns.next()
      say(mattia)

      first.joke.failure(RuntimeException("LLM is down"))
      val second = puns.next()
      assert(second.messages == Seq(mattia))
      second.joke.success("joke 1")
      assert(Await.result(chat.awaitReplies(1), 5.seconds).map(_.text) == Vector("joke 1"))
    }
  }
