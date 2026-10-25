package dadbot.part1

import dadbot.common.{ChatMessage, PunGenerator, RecordingChat, Reply, Script}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.slf4j.LoggerFactory
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** Plays Effect's conversation twice: against the naive webhook handler, then against sharded Dads. */
@main def part1(): Unit =
  val log = LoggerFactory.getLogger("Program")
  val puns = PunGenerator(thinking = 2.seconds)

  // Effect's script: Sebastian, a second later Mattia (same chat) and Tim (another chat).
  def play(post: (String, ChatMessage) => Unit): Unit =
    def say(chat: String, message: ChatMessage): Unit =
      log.info(s"$chat · ${message.from}: ${message.text}")
      post(chat, message)
    say(Script.engineering, Script.sebastian)
    Thread.sleep(1000)
    say(Script.engineering, Script.mattia)
    say(Script.random, Script.tim)

  def report(replies: Vector[Reply]): Unit =
    val repeats = replies.groupBy(_.chat).collect {
      case (chat, rs) if rs.map(_.text).distinct.size < rs.size => chat
    }
    if repeats.isEmpty then log.info("No chat heard the same joke twice.")
    else log.warn(s"Repeated joke in ${repeats.mkString(", ")}!")

  log.info("--- Naive webhook handler ---")
  locally {
    given ExecutionContext = ExecutionContext.global
    val chat = RecordingChat()
    val handler = NaiveHandler(JokeDb(), puns, chat)
    play((chatId, message) => handler.onMessage(chatId, message): Unit)
    report(Await.result(chat.awaitReplies(3), 10.seconds))
  }

  log.info("--- Sharded Dad entities ---")
  val system = ActorSystem(Behaviors.empty[Nothing], "DadBot")
  try
    val chat = RecordingChat()
    Dad.init(system, puns, chat)
    val sharding = ClusterSharding(system)
    play((chatId, message) => sharding.entityRefFor(Dad.TypeKey, chatId) ! Dad.NewMessage(message.from, message.text))
    report(Await.result(chat.awaitReplies(3), 15.seconds))
  finally
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit
