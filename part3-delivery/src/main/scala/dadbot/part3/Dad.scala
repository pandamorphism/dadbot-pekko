package dadbot.part3

import dadbot.common.{Chat, ChatMessage, PunGenerator}
import org.apache.pekko.Done
import org.apache.pekko.actor.NoSerializationVerificationNeeded
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** One Dad per chat, now event sourced. He saves every message before he acknowledges it, and saves the joke once it is
  * posted. Whatever he received but hasn't joked about yet is in the journal, so a Dad who restarts on another node
  * after a crash picks up where the old one stopped.
  */
object Dad:
  sealed trait Command

  /** Dad replies `Done` once the message is saved, which is when the webhook may tell the chat it got it. */
  final case class NewMessage(from: String, text: String, replyTo: ActorRef[Done]) extends Command with CborSerializable
  // Dad sends these to himself, so they never leave the node. `attempt` tells a stale answer (from before a restart
  // or an earlier failure) from the joke he is waiting for.
  private case object Quiet extends Command with NoSerializationVerificationNeeded
  private final case class JokeReady(attempt: Int, joke: String, batchSize: Int)
      extends Command
      with NoSerializationVerificationNeeded
  private final case class JokePosted(attempt: Int, joke: String, batchSize: Int)
      extends Command
      with NoSerializationVerificationNeeded
  private final case class JokeFailed(attempt: Int, reason: String)
      extends Command
      with NoSerializationVerificationNeeded
  private case object Idle extends Command with NoSerializationVerificationNeeded
  // The shard sends this when it passivates Dad.
  private case object Stop extends Command with NoSerializationVerificationNeeded

  sealed trait Event extends CborSerializable
  final case class MessageReceived(from: String, text: String) extends Event

  /** The joke covers the first `batchSize` unread messages. Later ones arrived while Dad was thinking. */
  final case class JokeTold(joke: String, batchSize: Int) extends Event

  /** Everything the journal knows about a chat: the jokes told in it and the messages no joke has covered yet. */
  final case class State(told: Set[String], unread: Vector[ChatMessage]) extends CborSerializable:
    def apply(event: Event): State = event match
      case MessageReceived(from, text) => copy(unread = unread :+ ChatMessage(from, text))
      case JokeTold(joke, batchSize)   => State(told + joke, unread.drop(batchSize))

  object State:
    val empty: State = State(Set.empty, Vector.empty)

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Dad")

  /** Effect's `timeoutOption("2500 millis")`. */
  val QuietPeriod: FiniteDuration = 2500.millis

  /** How long Dad stays around with nothing unread. Remember-entities turns off automatic passivation. */
  val IdleTimeout: FiniteDuration = 30.seconds

  def init(
      system: ActorSystem[?],
      puns: PunGenerator,
      chat: Chat,
      quietPeriod: FiniteDuration = QuietPeriod,
      idleTimeout: FiniteDuration = IdleTimeout
  ): ActorRef[ShardingEnvelope[Command]] =
    ClusterSharding(system).init(
      Entity(TypeKey) { ctx =>
        // With remember-entities, a Dad that stops himself is started again. Passivating through the shard is what
        // makes the shard forget him.
        val passivate = (self: ActorRef[Command]) => ctx.shard ! ClusterSharding.Passivate(self)
        Dad(ctx.entityId, puns, chat, passivate, quietPeriod, idleTimeout)
      }.withStopMessage(Stop)
    )

  /** The default extractor's shard id: `abs(entityId.hashCode) % number-of-shards` (1000). */
  def shardOf(chatId: String): String = (math.abs(chatId.hashCode) % 1000).toString

  def apply(
      chatId: String,
      puns: PunGenerator,
      chat: Chat,
      passivate: ActorRef[Command] => Unit,
      quietPeriod: FiniteDuration = QuietPeriod,
      idleTimeout: FiniteDuration = IdleTimeout
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers(timers =>
        new Dad(chatId, puns, chat, passivate, quietPeriod, idleTimeout, ctx, timers).behavior
      )
    }

private final class Dad(
    chatId: String,
    puns: PunGenerator,
    chat: Chat,
    passivate: ActorRef[Dad.Command] => Unit,
    quietPeriod: FiniteDuration,
    idleTimeout: FiniteDuration,
    ctx: ActorContext[Dad.Command],
    timers: TimerScheduler[Dad.Command]
):
  import Dad.*

  // The joke Dad is working on, if any. Not saved: after a crash he simply starts thinking again.
  private var thinking: Option[Int] = None
  private var attempts = 0

  def behavior: EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      PersistenceId(TypeKey.name, chatId),
      State.empty,
      onCommand,
      (state, event) => state(event)
    ).receiveSignal { case (state, RecoveryCompleted) =>
      ctx.log.info(
        "Dad for {} has entered the chat on {} ({} jokes told, {} messages unread)",
        chatId,
        Node.name(ctx.system),
        state.told.size,
        state.unread.size
      )
      thinking = None
      next(state)
    }
      // If the database is down, Dad restarts, replays the journal and tries again. Meanwhile asks time out.
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2))

  // Starting a timer with the same key cancels the previous one, so every message restarts the quiet period.
  private def waitForQuiet(): Unit = timers.startSingleTimer(Quiet, Quiet, quietPeriod)

  private def next(state: State): Unit =
    if state.unread.nonEmpty then waitForQuiet()
    else timers.startSingleTimer(Idle, Idle, idleTimeout)

  private def onCommand(state: State, command: Command): Effect[Event, State] = command match
    case NewMessage(from, text, replyTo) =>
      Effect
        .persist[Event, State](MessageReceived(from, text))
        .thenRun { _ =>
          timers.cancel(Idle)
          // Messages that arrive while Dad is thinking wait for the next batch.
          if thinking.isEmpty then waitForQuiet()
        }
        .thenReply(replyTo)(_ => Done)

    case Quiet if thinking.isEmpty && state.unread.nonEmpty =>
      attempts += 1
      val attempt = attempts
      val batch = state.unread
      thinking = Some(attempt)
      ctx.pipeToSelf(puns.generate(batch, state.told)) {
        case Success(joke)  => JokeReady(attempt, joke, batch.size)
        case Failure(cause) => JokeFailed(attempt, cause.getMessage)
      }
      Effect.none

    case JokeReady(attempt, joke, batchSize) if thinking.contains(attempt) =>
      ctx.pipeToSelf(chat.reply(chatId, joke)) {
        case Success(_)     => JokePosted(attempt, joke, batchSize)
        case Failure(cause) => JokeFailed(attempt, cause.getMessage)
      }
      Effect.none

    // A crash between posting the joke and saving it means the batch is still unread, and Dad tells the joke again.
    case JokePosted(attempt, joke, batchSize) if thinking.contains(attempt) =>
      Effect.persist(JokeTold(joke, batchSize)).thenRun { state =>
        thinking = None
        next(state)
      }

    // The messages stay unread, so Dad tries again once the chat is quiet.
    case JokeFailed(attempt, reason) if thinking.contains(attempt) =>
      ctx.log.warn("Dad for {} lost his train of thought: {}", chatId, reason)
      thinking = None
      next(state)
      Effect.none

    case Idle if thinking.isEmpty && state.unread.isEmpty =>
      passivate(ctx.self)
      Effect.none

    case Stop =>
      Effect.stop()

    case _ =>
      Effect.none
