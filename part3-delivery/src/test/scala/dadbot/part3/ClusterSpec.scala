package dadbot.part3

import com.typesafe.config.ConfigFactory
import dadbot.common.{PunGenerator, RecordingChat}
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Down, Join}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Three nodes in one JVM, sharing the journal in Postgres. Canceled when Postgres isn't running:
  * `docker compose up -d`.
  */
class ClusterSpec extends AnyWordSpec with BeforeAndAfterAll with Eventually:
  override given patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds))

  private val config = ConfigFactory
    .parseString("""
      pekko.remote.artery.canonical.port = 0
      pekko.cluster.seed-nodes = []
      pekko.actor.serialize-messages = on
      # terminate() stops a node without leaving the cluster or handing off its shards: a crash.
      pekko.coordinated-shutdown.run-by-actor-system-terminate = off
      # The split brain resolver's default (stable-after, 20 s) before a downed node is removed and its shards move.
      pekko.cluster.down-removal-margin = 2s
    """)
    .withFallback(Node.baseConfig)

  private lazy val postgres = Database.prepare(config = config)

  private val chat = RecordingChat()
  private var nodes = Vector.empty[ActorSystem[Nothing]]

  private def start(): Unit =
    nodes = Vector.fill(3)(ActorSystem(Behaviors.empty[Nothing], "DadBot", config))
    nodes.foreach(node => Cluster(node).manager ! Join(Cluster(nodes.head).selfMember.address))
    eventually {
      nodes.foreach(node => assert(Cluster(node).state.members.count(_.status == MemberStatus.Up) == 3))
    }
    nodes.foreach(Dad.init(_, PunGenerator(thinking = 50.millis), chat, quietPeriod = 300.millis))

  override def afterAll(): Unit =
    nodes.foreach(node => Await.ready(CoordinatedShutdown(node).run(CoordinatedShutdown.UnknownReason), 30.seconds))

  private def save(via: ActorSystem[?], chatId: String, from: String, text: String): Unit =
    given Timeout = 10.seconds
    Await.result(
      ClusterSharding(via).entityRefFor(Dad.TypeKey, chatId).ask[Done](Dad.NewMessage(from, text, _)),
      15.seconds
    )

  "Event-sourced Dads on three nodes" should {
    "answer a saved message after the node hosting its Dad crashes, without repeating a joke" in {
      assume(postgres, "Postgres isn't running: docker compose up -d")
      start()

      save(nodes(0), "#engineering", "Sebastian", "Why is this test flaky?")
      Await.result(chat.awaitReplies(1), 10.seconds)

      val host = Node.hostOf(nodes.head, "#engineering").get
      val crashing = nodes.find(node => Cluster(node).selfMember.address == host).get
      val survivor = nodes.find(_ ne crashing).get

      // Saved, then the node crashes before the chat has been quiet long enough for a joke.
      save(survivor, "#engineering", "Sebastian", "Still flaky?")
      crashing.terminate()
      Await.ready(crashing.whenTerminated, 10.seconds)
      nodes = nodes.filter(_ ne crashing)
      Cluster(survivor).manager ! Down(host)

      // Nobody posts again: remember-entities starts the Dad elsewhere, and he finds the message in the journal.
      val replies = Await.result(chat.awaitReplies(2), 30.seconds)
      assert(replies.map(_.text).distinct.size == 2)
      assert(Node.hostOf(survivor, "#engineering").exists(_ != host))
    }
  }
