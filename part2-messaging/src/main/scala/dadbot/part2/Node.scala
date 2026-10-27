package dadbot.part2

import com.typesafe.config.{Config, ConfigFactory}
import dadbot.common.{Chat, PunGenerator}
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.Cluster as ClassicCluster
import org.apache.pekko.cluster.sharding.ShardRegion.ClusterShardingStats
import org.apache.pekko.cluster.sharding.typed.GetClusterShardingStats
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.duration.*

/** One DadBot node: a cluster member that hosts Dads, serves the webhook and exposes Pekko Management. */
object Node:
  val BasePort = 25520

  private val log = LoggerFactory.getLogger("Node")

  /** Node `n` listens on 2552n (cluster), 808n (webhook) and 855n (management). */
  def config(n: Int): Config =
    ConfigFactory
      .parseString(s"""
        dadbot.node = "node-$n"
        dadbot.webhook.port = ${8080 + n}
        pekko.remote.artery.canonical.port = ${BasePort + n}
        pekko.management.http.port = ${8550 + n}
      """)
      .withFallback(ConfigFactory.load())

  def name(system: ActorSystem[?]): String = system.settings.config.getString("dadbot.node")

  /** Asks the cluster which node hosts the chat's shard right now, if it has been allocated. */
  def hostOf(system: ActorSystem[?], chatId: String): Option[Address] =
    given ActorSystem[?] = system
    given Timeout = 5.seconds
    val stats = Await.result(
      ClusterSharding(system).shardState.ask[ClusterShardingStats](GetClusterShardingStats(Dad.TypeKey, 3.seconds, _)),
      5.seconds
    )
    stats.regions.collectFirst { case (address, region) if region.stats.contains(Dad.shardOf(chatId)) => address }

  def start(n: Int, puns: PunGenerator, chat: Chat, endpoints: Boolean = true): ActorSystem[Nothing] =
    ActorSystem(guardian(puns, chat, endpoints), "DadBot", config(n))

  private def guardian(puns: PunGenerator, chat: Chat, endpoints: Boolean): Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      val system = ctx.system
      Dad.init(system, puns, chat)
      if endpoints then
        given ActorSystem[Nothing] = system
        val sharding = ClusterSharding(system)
        val webhook = Webhook.route((chatId, message) => sharding.entityRefFor(Dad.TypeKey, chatId) ! message)
        Http().newServerAt("127.0.0.1", system.settings.config.getInt("dadbot.webhook.port")).bind(webhook)
        PekkoManagement(system).start()
        // Pekko's own logs are at WARN (common/logback.xml), so say when the node is ready. The callback runs on
        // another thread, where ctx.log must not be used.
        ClassicCluster(system).registerOnMemberUp(
          log.info(
            "{} is Up: webhook on :{}, management on :{}",
            name(system),
            system.settings.config.getInt("dadbot.webhook.port"),
            system.settings.config.getInt("pekko.management.http.port")
          )
        )
      Behaviors.empty
    }

/** Starts node `n` (0, 1 or 2) and runs until it leaves the cluster or the process is stopped. */
@main def startNode(n: Int): Unit =
  val system = Node.start(n, PunGenerator(thinking = 2.seconds), Chat.logging)
  Await.ready(system.whenTerminated, Duration.Inf): Unit
