package dadbot.part2

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable

class WebhookSpec extends AnyWordSpec with ScalatestRouteTest:
  // The route doesn't need a cluster, so don't start one on application.conf's port.
  override def testConfigSource: String = "pekko.actor.provider = local"

  "The webhook" should {
    "hand the message to the chat's Dad and answer without waiting for him" in {
      val told = mutable.Buffer.empty[(String, Dad.NewMessage)]
      val route = Webhook.route((chatId, message) => told += chatId -> message)

      val body = HttpEntity(ContentTypes.`application/json`, """{"from":"Tim","text":"Is CI down?"}""")
      Post("/chats/%23random/messages", body) ~> route ~> check {
        assert(status == StatusCodes.Accepted)
      }
      assert(told.toSeq == Seq("#random" -> Dad.NewMessage("Tim", "Is CI down?")))
    }
  }
