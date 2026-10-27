package dadbot.part2

import dadbot.common.{Chat, ChatMessage, PunGenerator}
import org.apache.pekko.actor.NoSerializationVerificationNeeded
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** One Dad per chat. He waits until the chat has been quiet for a while, then tells one pun about everything said. */
object Dad:
  sealed trait Command
  // The only message that crosses the wire, so the only one that needs a serializer.
  final case class NewMessage(from: String, text: String) extends Command with CborSerializable
  // Dad sends these to himself, so they never leave the node.
  private case object Quiet extends Command with NoSerializationVerificationNeeded
  private final case class JokeReady(joke: String) extends Command with NoSerializationVerificationNeeded
  private final case class JokeFailed(reason: String) extends Command with NoSerializationVerificationNeeded

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Dad")

  /** Effect's `timeoutOption("2500 millis")`. */
  val QuietPeriod: FiniteDuration = 2500.millis

  def init(
      system: ActorSystem[?],
      puns: PunGenerator,
      chat: Chat,
      quietPeriod: FiniteDuration = QuietPeriod
  ): ActorRef[ShardingEnvelope[Command]] =
    ClusterSharding(system).init(Entity(TypeKey)(ctx => Dad(ctx.entityId, puns, chat, quietPeriod)))

  /** The default extractor's shard id: `abs(entityId.hashCode) % number-of-shards` (1000). */
  def shardOf(chatId: String): String = (math.abs(chatId.hashCode) % 1000).toString

  def apply(
      chatId: String,
      puns: PunGenerator,
      chat: Chat,
      quietPeriod: FiniteDuration = QuietPeriod
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      ctx.log.info("Dad for {} has entered the chat on {}", chatId, Node.name(ctx.system))
      Behaviors.withTimers(timers => new Dad(chatId, puns, chat, quietPeriod, ctx, timers).idle(Set.empty))
    }

private final class Dad(
    chatId: String,
    puns: PunGenerator,
    chat: Chat,
    quietPeriod: FiniteDuration,
    ctx: ActorContext[Dad.Command],
    timers: TimerScheduler[Dad.Command]
):
  import Dad.*

  // Starting a timer with the same key cancels the previous one, so every message restarts the quiet period.
  private def waitForQuiet(): Unit = timers.startSingleTimer(Quiet, Quiet, quietPeriod)

  def idle(told: Set[String]): Behavior[Command] =
    Behaviors.receiveMessagePartial { case NewMessage(from, text) =>
      waitForQuiet()
      collecting(told, Vector(ChatMessage(from, text)))
    }

  private def collecting(told: Set[String], batch: Vector[ChatMessage]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case NewMessage(from, text) =>
        waitForQuiet()
        collecting(told, batch :+ ChatMessage(from, text))
      case Quiet =>
        ctx.pipeToSelf(puns.generate(batch, told)) {
          case Success(joke)  => JokeReady(joke)
          case Failure(cause) => JokeFailed(cause.getMessage)
        }
        thinking(told, Vector.empty)
    }

  // Messages that arrive while Dad is thinking become the next batch, like Effect's `unread` queue.
  private def thinking(told: Set[String], unread: Vector[ChatMessage]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case NewMessage(from, text) =>
        thinking(told, unread :+ ChatMessage(from, text))
      case JokeReady(joke) =>
        chat.reply(chatId, joke)
        next(told + joke, unread)
      case JokeFailed(reason) =>
        ctx.log.warn("Dad for {} lost his train of thought: {}", chatId, reason)
        next(told, unread)
    }

  private def next(told: Set[String], unread: Vector[ChatMessage]): Behavior[Command] =
    if unread.isEmpty then idle(told)
    else
      waitForQuiet()
      collecting(told, unread)
