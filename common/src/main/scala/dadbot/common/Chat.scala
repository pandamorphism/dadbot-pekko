package dadbot.common

import org.slf4j.LoggerFactory
import scala.concurrent.{Future, Promise}

/** Posts DadBot's replies to a chat. */
trait Chat:
  def reply(chat: String, text: String): Future[Unit]

object Chat:
  private val log = LoggerFactory.getLogger("DadBot")

  /** Like Effect's ChatLayer: the "chat" is the log. */
  val logging: Chat = (chat, text) => Future.successful(log.info(s"$chat · DadBot: $text"))

final case class Reply(chat: String, text: String)

/** Remembers every reply so programs and tests can wait for them instead of sleeping. */
final class RecordingChat(underlying: Chat = Chat.logging) extends Chat:
  private var replies = Vector.empty[Reply]
  private var waiting = List.empty[(Int, Promise[Vector[Reply]])]

  def reply(chat: String, text: String): Future[Unit] =
    synchronized {
      replies :+= Reply(chat, text)
      val (done, pending) = waiting.partition((count, _) => count <= replies.size)
      waiting = pending
      done.foreach((_, promise) => promise.success(replies))
    }
    underlying.reply(chat, text)

  def all: Vector[Reply] = synchronized(replies)

  /** Completes once at least `count` replies were posted. */
  def awaitReplies(count: Int): Future[Vector[Reply]] = synchronized {
    if replies.size >= count then Future.successful(replies)
    else
      val promise = Promise[Vector[Reply]]()
      waiting ::= count -> promise
      promise.future
  }
