package dadbot.part1

import com.typesafe.config.ConfigFactory
import dadbot.common.{PunGenerator, RecordingChat, Script}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.Await
import scala.concurrent.duration.*

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

  private val chat = RecordingChat()
  private val sharding = ClusterSharding(system)

  override def beforeAll(): Unit =
    super.beforeAll()
    Cluster(system).manager ! Join(Cluster(system).selfMember.address)
    Dad.init(system, PunGenerator(thinking = 200.millis), chat)

  "A sharded Dad" should {
    "never repeat a joke in his chat, however fast messages arrive" in {
      // Two separate refs, same id: callers only know the chat, never where its Dad lives.
      sharding.entityRefFor(Dad.TypeKey, Script.engineering) ! Dad.NewMessage("Sebastian", "Why is this test flaky?")
      sharding.entityRefFor(Dad.TypeKey, Script.engineering) ! Dad.NewMessage("Mattia", "It's flaky for me too")
      sharding.entityRefFor(Dad.TypeKey, Script.engineering) ! Dad.NewMessage("Sebastian", "Still flaky")

      val replies = Await.result(chat.awaitReplies(3), 10.seconds).filter(_.chat == Script.engineering)
      assert(replies.size == 3)
      assert(replies.map(_.text).distinct.size == 3)
    }

    "keep a separate joke history per chat" in {
      sharding.entityRefFor(Dad.TypeKey, Script.random) ! Dad.NewMessage("Tim", "Is CI down?")

      val reply = Await.result(chat.awaitReplies(4), 10.seconds).filter(_.chat == Script.random)
      assert(reply.map(_.text) == Vector(PunGenerator.jokes.head))
    }
  }
