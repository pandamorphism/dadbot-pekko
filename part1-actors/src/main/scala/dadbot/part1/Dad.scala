package dadbot.part1

import dadbot.common.{Chat, ChatMessage, PunGenerator}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import scala.util.{Failure, Success}

/** One Dad per chat: a sharded entity that owns the chat's joke history and handles one message at a time. */
object Dad:
  sealed trait Command
  final case class NewMessage(from: String, text: String) extends Command
  private final case class JokeReady(joke: String) extends Command
  private final case class JokeFailed(cause: Throwable) extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Dad")

  /** Registers the entity type on this node; callers then address a Dad by chat id only. */
  def init(system: ActorSystem[?], puns: PunGenerator, chat: Chat): ActorRef[ShardingEnvelope[Command]] =
    ClusterSharding(system).init(Entity(TypeKey)(ctx => Dad(ctx.entityId, puns, chat)))

  def apply(chatId: String, puns: PunGenerator, chat: Chat): Behavior[Command] =
    Behaviors.setup { ctx =>
      ctx.log.info("Dad for {} has entered the chat", chatId)

      Behaviors.withStash(capacity = 100) { stash =>
        // The mailbox serialises messages, but the pun arrives asynchronously, so "one at a time" means
        // stashing new messages until the current joke is told.
        def listening(told: Set[String]): Behavior[Command] =
          Behaviors.receiveMessagePartial { case NewMessage(from, text) =>
            ctx.pipeToSelf(puns.generate(Seq(ChatMessage(from, text)), told)) {
              case Success(joke)  => JokeReady(joke)
              case Failure(cause) => JokeFailed(cause)
            }
            thinking(told)
          }

        def thinking(told: Set[String]): Behavior[Command] =
          Behaviors.receiveMessage {
            case JokeReady(joke) =>
              chat.reply(chatId, joke)
              stash.unstashAll(listening(told + joke))
            case JokeFailed(cause) =>
              ctx.log.warn("Dad for {} lost his train of thought: {}", chatId, cause.getMessage)
              stash.unstashAll(listening(told))
            case message: NewMessage =>
              stash.stash(message)
              Behaviors.same
          }

        listening(Set.empty)
      }
    }
