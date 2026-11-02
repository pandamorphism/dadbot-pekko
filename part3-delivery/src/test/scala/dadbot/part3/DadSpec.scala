package dadbot.part3

import com.typesafe.config.ConfigFactory
import dadbot.common.{Chat, ChatMessage, PunGenerator, RecordingChat}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
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

/** Posts the first joke and never confirms it: what the chat looks like to a Dad whose node crashes right after
  * posting. Later jokes are confirmed as usual.
  */
final class PostThenHang(underlying: RecordingChat) extends Chat:
  private val first = AtomicBoolean(true)
  def reply(chat: String, text: String): Future[Unit] =
    val posted = underlying.reply(chat, text)
    if first.getAndSet(false) then Promise[Unit]().future else posted

/** Dad on his own, with Pekko's in-memory journal. `restart()` stops him and recovers him from that journal, as a new
  * node would after a crash.
  */
class DadSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(EventSourcedBehaviorTestKit.config)
        .withFallback(Node.baseConfig)
    )
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with Eventually:

  override given patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  private val quiet = 300.millis
  private val sebastian = ChatMessage("Sebastian", "Why is this test flaky?")
  private val mattia = ChatMessage("Mattia", "It's flaky for me too")
  private val tim = ChatMessage("Tim", "Is CI down?")

  private var puns = ScriptedPuns()
  private var replies = RecordingChat()
  @volatile private var passivated = false
  // A fresh chat per test, so a Dad left over from the previous test can't write to this one's journal.
  private var tests = 0
  private def chatId = s"#engineering-$tests"
  private var dad: EventSourcedBehaviorTestKit[Dad.Command, Dad.Event, Dad.State] = scala.compiletime.uninitialized

  /** A Dad for the chat, who recovers from whatever the journal holds for it. */
  private def newDad(chat: Chat = replies, idle: FiniteDuration = 1.minute) =
    EventSourcedBehaviorTestKit[Dad.Command, Dad.Event, Dad.State](
      system,
      Dad(chatId, puns, chat, _ => passivated = true, quiet, idle)
    )

  override def beforeEach(): Unit =
    puns = ScriptedPuns()
    replies = RecordingChat()
    passivated = false
    tests += 1
    dad = newDad()

  private def say(m: ChatMessage) =
    val result = dad.runCommand[Done](Dad.NewMessage(m.from, m.text, _))
    assert(result.reply == Done)
    result

  private def jokes(count: Int): Vector[String] = Await.result(replies.awaitReplies(count), 5.seconds).map(_.text)

  "An event-sourced Dad" should {
    "save a message before he acknowledges it" in {
      val result = say(sebastian)
      assert(result.events == Seq(Dad.MessageReceived(sebastian.from, sebastian.text)))
      assert(result.state.unread == Vector(sebastian))
    }

    "tell one joke about everything said before the chat went quiet, and save it" in {
      say(sebastian)
      say(mattia)
      val request = puns.next()
      assert(request.messages == Seq(sebastian, mattia))
      request.joke.success("joke 1")

      assert(jokes(1) == Vector("joke 1"))
      eventually(assert(dad.getState() == Dad.State(Set("joke 1"), Vector.empty)))
    }

    "keep what is said while he is thinking for the next joke" in {
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
    }

    "keep the messages when the pun fails, and try again" in {
      say(sebastian)
      puns.next().joke.failure(RuntimeException("LLM is down"))
      say(mattia)
      assert(puns.next().messages == Seq(sebastian, mattia))
    }

    "answer a message he received before a crash" in {
      say(sebastian)
      dad.restart()
      // Nobody posts again. The new Dad finds the message in the journal.
      val request = puns.next()
      assert(request.messages == Seq(sebastian))
      request.joke.success("joke 1")
      assert(jokes(1) == Vector("joke 1"))
    }

    "remember the jokes he told before a crash" in {
      say(sebastian)
      puns.next().joke.success("joke 1")
      eventually(assert(dad.getState().told == Set("joke 1")))

      dad.restart()
      say(mattia)
      val request = puns.next()
      assert(request.messages == Seq(mattia))
      assert(request.told == Set("joke 1"))
    }

    "tell the same joke again if he crashes after posting it but before saving it" in {
      dad = newDad(chat = PostThenHang(replies))
      say(sebastian)
      puns.next().joke.success("joke 1")
      assert(jokes(1) == Vector("joke 1"))

      dad.restart() // the joke was posted, but JokeTold never written
      val again = puns.next()
      assert(again.messages == Seq(sebastian))
      assert(again.told.isEmpty)
      again.joke.success("joke 1")
      assert(jokes(2) == Vector("joke 1", "joke 1"), "at-least-once: part 4 makes Dad idempotent")
    }

    "passivate once he has been idle with nothing unread" in {
      dad = newDad(idle = 200.millis)
      say(sebastian)
      Thread.sleep(500)
      assert(!passivated, "Dad must not passivate with a message unread")
      puns.next().joke.success("joke 1")
      eventually(assert(passivated))
    }
  }
