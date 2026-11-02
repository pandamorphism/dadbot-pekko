package dadbot.part3

import com.typesafe.config.{Config, ConfigFactory}
import io.r2dbc.spi.{Connection, ValidationDepth}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Try

/** The Postgres behind the journal, through the connection pool the r2dbc plugin uses. */
object Database:
  private val ConnectionFactory = "pekko.persistence.r2dbc.connection-factory"

  /** Asks the server itself, not just the pool. */
  def isUp(system: ActorSystem[?]): Future[Boolean] =
    withConnection(system)(connection => Source.fromPublisher(connection.validate(ValidationDepth.REMOTE)))
      .map(_.contains(true))(using system.executionContext)

  /** Clears the journal, so every run of the demo starts with Dads who have never told a joke. */
  def reset(system: ActorSystem[?]): Future[Done] =
    withConnection(system) { connection =>
      Source
        .fromPublisher(connection.createStatement("TRUNCATE event_journal, snapshot").execute())
        .flatMapConcat(result => Source.fromPublisher(result.getRowsUpdated))
    }.map(_ => Done)(using system.executionContext)

  /** Checks Postgres (and clears the journal if `reset`) from a throwaway local actor system, before any node starts.
    */
  def prepare(reset: Boolean = true, config: Config = Node.baseConfig): Boolean =
    val system = ActorSystem(
      Behaviors.empty[Nothing],
      "DadBot-setup",
      ConfigFactory.parseString("pekko.actor.provider = local").withFallback(config)
    )
    try
      Try(Await.result(isUp(system), 10.seconds)).getOrElse(false) && {
        if reset then Await.result(Database.reset(system), 10.seconds)
        true
      }
    finally system.terminate()

  private def withConnection[A](system: ActorSystem[?])(use: Connection => Source[A, ?]): Future[Option[A]] =
    given ActorSystem[?] = system
    given ExecutionContext = system.executionContext
    val factory = ConnectionFactoryProvider(system).connectionFactoryFor(ConnectionFactory)
    Source.fromPublisher(factory.create()).runWith(Sink.head).flatMap { connection =>
      use(connection)
        .runWith(Sink.lastOption)
        .andThen(_ => Source.fromPublisher(connection.close()).runWith(Sink.ignore))
    }

/** Pekko Management readiness check (`GET /ready`): a node that can't save messages shouldn't get webhook calls. */
final class DatabaseCheck(system: org.apache.pekko.actor.ActorSystem) extends (() => Future[Boolean]):
  import org.apache.pekko.actor.typed.scaladsl.adapter.*
  def apply(): Future[Boolean] = Database.isUp(system.toTyped)
