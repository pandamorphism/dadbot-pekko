package dadbot.common

import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration

/** Writes a pun about the messages, avoiding the jokes already `told`. */
trait PunGenerator:
  def generate(messages: Seq[ChatMessage], told: Set[String]): Future[String]

object PunGenerator:
  // Same jokes as Effect's PunGenerator.ts, so both bots tell the same story.
  val jokes: Vector[String] = Vector(
    "I'd tell you a UDP joke, but you might not get it.",
    "It's not flaky. It's just going through a phase.",
    "I'd tell you a TCP joke, but I'd have to keep repeating it until you got it.",
    "Why did the developer go broke? He used up all his cache.",
    "I'm reading a book about anti-gravity. It's impossible to put down.",
    "There are 10 kinds of people: those who understand binary and those who don't.",
    "I would tell you a joke about race conditions, but you might get the punchline first.",
    "Why do programmers prefer dark mode? Because light attracts bugs.",
    "I told my kids a joke about recursion. Then I told them a joke about recursion.",
    "My code doesn't have bugs. It just develops random features.",
    "Why was the function so calm? It had no side effects.",
    "I'd make a joke about null, but it wouldn't be worth anything.",
    "Debugging is like being the detective in a crime movie where you're also the murderer.",
    "Why did the commit break up with the branch? It needed more space to grow.",
    "I tried to come up with a joke about distributed systems, but it never reached consensus."
  )

  /** Picks the first untold joke after `thinking`. The delay plays the LLM call and opens the race window. */
  def apply(thinking: FiniteDuration): PunGenerator = (_, told) =>
    val joke = Promise[String]()
    CompletableFuture
      .delayedExecutor(thinking.toMillis, TimeUnit.MILLISECONDS)
      .execute(() => joke.success(jokes.find(!told(_)).getOrElse(jokes.head)))
    joke.future
