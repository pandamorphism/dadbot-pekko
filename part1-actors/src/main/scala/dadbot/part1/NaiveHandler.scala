package dadbot.part1

import dadbot.common.{Chat, ChatMessage, PunGenerator}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/** Shared joke history, standing in for the `db` in Effect's part 1 sketch. */
final class JokeDb(using ExecutionContext):
  private val told = TrieMap.empty[String, Set[String]]

  def getJokesTold(chatId: String): Future[Set[String]] = Future(told.getOrElse(chatId, Set.empty))
  def setJokesTold(chatId: String, jokes: Set[String]): Future[Unit] = Future(told.update(chatId, jokes))

/** The webhook handler from Effect's part 1: read, think, write, reply. Two calls for one chat can interleave, so both
  * read the same history and tell the same joke.
  */
final class NaiveHandler(db: JokeDb, puns: PunGenerator, chat: Chat)(using ExecutionContext):
  def onMessage(chatId: String, message: ChatMessage): Future[Unit] =
    for
      told <- db.getJokesTold(chatId)
      joke <- puns.generate(Seq(message), told)
      _ <- db.setJokesTold(chatId, told + joke)
      _ <- chat.reply(chatId, joke)
    yield ()
