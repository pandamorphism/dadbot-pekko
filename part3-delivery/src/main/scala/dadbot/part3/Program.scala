package dadbot.part3

import com.typesafe.config.ConfigFactory
import dadbot.common.{ChatMessage, PunGenerator, RecordingChat, Reply, Script}
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Down}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Plays Effect's conversation on three nodes in one JVM, then crashes the node hosting #engineering while its Dad has
  * a message he hasn't joked about yet.
  */
@main def part3(): Unit =
  val log = LoggerFactory.getLogger("Program")
  if !Database.prepare() then
    log.error("Postgres isn't reachable on localhost:5432. Start it with `docker compose up -d`.")
    return

  val chat = RecordingChat()
  // `terminate()` normally runs coordinated shutdown: hand off shards, leave the cluster. With this off, it just
  // stops the node, which is as close to a crash as one JVM gets. Nodes that end normally run it explicitly.
  val crashable = ConfigFactory.parseString("pekko.coordinated-shutdown.run-by-actor-system-terminate = off")
  val nodes = Vector.tabulate(3)(n =>
    Node.start(n, PunGenerator(thinking = 2.seconds), chat, endpoints = false, overrides = crashable)
  )

  // What the webhook does: ask Dad, and only answer the chat once he has saved the message.
  def post(via: ActorSystem[?], chatId: String, message: ChatMessage): Unit =
    given Timeout = Node.SaveTimeout
    val saved =
      ClusterSharding(via).entityRefFor(Dad.TypeKey, chatId).ask[Done](Dad.NewMessage(message.from, message.text, _))
    Await.result(saved, 10.seconds): Done
    log.info(s"$chatId · ${message.from}: ${message.text}  (via ${Node.name(via)}, saved)")

  def hostOf(chatId: String, among: Seq[ActorSystem[?]]): ActorSystem[?] =
    val address = Node.hostOf(among.head, chatId).get
    among.find(node => Cluster(node).selfMember.address == address).get

  def report(replies: Vector[Reply]): Unit =
    val repeats = replies.groupBy(_.chat).collect { case (c, rs) if rs.map(_.text).distinct.size < rs.size => c }
    if repeats.isEmpty then log.info("No chat heard the same joke twice.")
    else log.warn(s"Repeated joke in ${repeats.mkString(", ")}!")

  var alive = nodes
  try
    while nodes.exists(node => Cluster(node).state.members.count(_.status == MemberStatus.Up) < 3) do Thread.sleep(100)
    log.info("--- Three nodes are up ---")

    post(nodes(0), Script.engineering, Script.sebastian)
    Thread.sleep(1000)
    post(nodes(1), Script.engineering, Script.mattia)
    post(nodes(2), Script.random, Script.tim)
    Await.result(chat.awaitReplies(2), 15.seconds)

    val host = hostOf(Script.engineering, nodes)
    val survivor = nodes.find(_ ne host).get
    post(survivor, Script.engineering, ChatMessage("Sebastian", "Still flaky?"))

    log.info(s"--- ${Node.name(host)} crashes before Dad for ${Script.engineering} has told his joke ---")
    host.terminate()
    Await.ready(host.whenTerminated, 30.seconds)
    alive = nodes.filter(_ ne host)
    // The split brain resolver would down it after `stable-after` (20 s) without changes. Do it now. Its shards
    // still only move once it is removed, after `down-removal-margin`, which the split brain resolver also sets
    // to `stable-after`: time for a node on the losing side of a partition to notice and stop its Dads.
    Cluster(survivor).manager ! Down(Cluster(host).selfMember.address)
    log.info(s"--- ${Node.name(host)} is down. Its shards move in about 20 s ---")

    // Nobody posts again: remember-entities restarts the Dad on a surviving node, and the journal gives him the
    // message and the joke he already told.
    report(Await.result(chat.awaitReplies(3), 60.seconds))
    log.info(
      s"${Script.engineering} → shard ${Dad.shardOf(Script.engineering)} → ${Node.name(hostOf(Script.engineering, alive))}"
    )
  finally
    // One at a time: each node leaves the cluster gracefully before the next one starts to.
    alive.foreach { node =>
      Await.ready(CoordinatedShutdown(node).run(CoordinatedShutdown.UnknownReason), 30.seconds)
    }
