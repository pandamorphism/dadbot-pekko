package dadbot.part2

import dadbot.common.{ChatMessage, PunGenerator, RecordingChat, Reply, Script}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Leave}
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Plays Effect's part 2 conversation on a three-node cluster in one JVM, then takes the node hosting #engineering out
  * of the cluster.
  */
@main def part2(): Unit =
  val log = LoggerFactory.getLogger("Program")
  val chat = RecordingChat()
  val nodes = Vector.tabulate(3)(n => Node.start(n, PunGenerator(thinking = 2.seconds), chat, endpoints = false))

  // Each message enters through a different node, as if a load balancer had picked it.
  def post(via: ActorSystem[?], chatId: String, message: ChatMessage): Unit =
    log.info(s"$chatId · ${message.from}: ${message.text}  (via ${Node.name(via)})")
    ClusterSharding(via).entityRefFor(Dad.TypeKey, chatId) ! Dad.NewMessage(message.from, message.text)

  def hostOf(chatId: String): ActorSystem[?] =
    val address = Node.hostOf(nodes.head, chatId).get
    nodes.find(node => Cluster(node).selfMember.address == address).get

  def report(replies: Vector[Reply]): Unit =
    val repeats = replies.groupBy(_.chat).collect { case (c, rs) if rs.map(_.text).distinct.size < rs.size => c }
    if repeats.isEmpty then log.info("No chat heard the same joke twice.")
    else log.warn(s"Repeated joke in ${repeats.mkString(", ")}!")

  try
    while nodes.exists(node => Cluster(node).state.members.count(_.status == MemberStatus.Up) < 3) do Thread.sleep(100)
    log.info("--- Three nodes are up ---")

    post(nodes(0), Script.engineering, Script.sebastian)
    Thread.sleep(1000)
    post(nodes(1), Script.engineering, Script.mattia)
    post(nodes(2), Script.random, Script.tim)
    Await.result(chat.awaitReplies(2), 15.seconds)

    for chatId <- Seq(Script.engineering, Script.random) do
      log.info(s"$chatId → shard ${Dad.shardOf(chatId)} → ${Node.name(hostOf(chatId))}")

    val leaving = hostOf(Script.engineering)
    log.info(s"--- ${Node.name(leaving)} leaves the cluster ---")
    Cluster(leaving).manager ! Leave(Cluster(leaving).selfMember.address)
    Await.ready(leaving.whenTerminated, 30.seconds)

    // `ne`, not `!=`: an ActorSystem is an ActorRef to its guardian, and refs compare by path, which is the same
    // `pekko://DadBot/user` on every node in this JVM.
    val survivor = nodes.find(_ ne leaving).get
    post(survivor, Script.engineering, ChatMessage("Sebastian", "Still flaky?"))
    report(Await.result(chat.awaitReplies(3), 30.seconds))
  finally
    // One at a time: each node leaves the cluster gracefully before the next one starts to.
    nodes.foreach { node =>
      node.terminate()
      Await.ready(node.whenTerminated, 30.seconds)
    }
