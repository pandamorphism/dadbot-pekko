package dadbot.part2

import com.typesafe.config.ConfigFactory
import dadbot.common.{ChatMessage, PunGenerator, RecordingChat}
import java.util.concurrent.ConcurrentLinkedQueue
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Join, Leave}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Three cluster nodes in one JVM. `serialize-messages = on` makes every message go through its serializer, so a
  * message that couldn't cross the network fails here instead of in production.
  */
class ClusterSpec extends AnyWordSpec with BeforeAndAfterAll with Eventually:
  override given patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds))

  private val config = ConfigFactory
    .parseString("""
      pekko.remote.artery.canonical.port = 0
      pekko.cluster.seed-nodes = []
      pekko.actor.serialize-messages = on
    """)
    .withFallback(ConfigFactory.load())

  private val batches = ConcurrentLinkedQueue[Seq[ChatMessage]]()
  private val puns: PunGenerator =
    val underlying = PunGenerator(thinking = 50.millis)
    (messages, told) =>
      batches.add(messages)
      underlying.generate(messages, told)

  private val chat = RecordingChat()
  private val nodes = Vector.fill(3)(ActorSystem(Behaviors.empty[Nothing], "DadBot", config))

  override def beforeAll(): Unit =
    nodes.foreach(node => Cluster(node).manager ! Join(Cluster(nodes.head).selfMember.address))
    eventually {
      nodes.foreach(node => assert(Cluster(node).state.members.count(_.status == MemberStatus.Up) == 3))
    }
    nodes.foreach(Dad.init(_, puns, chat, quietPeriod = 300.millis))

  override def afterAll(): Unit =
    nodes.foreach(_.terminate())
    nodes.foreach(node => Await.ready(node.whenTerminated, 30.seconds))

  private def say(via: ActorSystem[?], chatId: String, from: String, text: String): Unit =
    ClusterSharding(via).entityRefFor(Dad.TypeKey, chatId) ! Dad.NewMessage(from, text)

  "Sharded Dads on three nodes" should {
    "receive a chat's messages in one Dad, whichever node they enter through" in {
      say(nodes(0), "#engineering", "Sebastian", "Why is this test flaky?")
      say(nodes(1), "#engineering", "Mattia", "It's flaky for me too")
      say(nodes(2), "#engineering", "Tim", "Same here")

      Await.result(chat.awaitReplies(1), 10.seconds)
      // One batch, but in no particular order: Pekko only orders messages per sender-receiver pair, and these
      // came through three different nodes.
      assert(batches.asScala.toSeq.map(_.map(_.from).toSet) == Seq(Set("Sebastian", "Mattia", "Tim")))
    }

    "start the Dad on another node after his node leaves the cluster" in {
      val host = Node.hostOf(nodes.head, "#engineering").get
      val leaving = nodes.find(node => Cluster(node).selfMember.address == host).get
      val survivor = nodes.find(_ ne leaving).get

      Cluster(leaving).manager ! Leave(host)
      Await.ready(leaving.whenTerminated, 30.seconds)
      eventually(assert(Cluster(survivor).state.members.count(_.status == MemberStatus.Up) == 2))

      say(survivor, "#engineering", "Sebastian", "Still flaky?")
      Await.result(chat.awaitReplies(2), 20.seconds)
      assert(Node.hostOf(survivor, "#engineering").exists(_ != host))
    }
  }
