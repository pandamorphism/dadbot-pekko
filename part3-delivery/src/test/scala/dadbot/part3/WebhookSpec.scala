package dadbot.part3

import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable
import scala.concurrent.Future

class WebhookSpec extends AnyWordSpec with ScalatestRouteTest:
  // The route doesn't need a cluster, so don't start one.
  override def testConfigSource: String = "pekko.actor.provider = local"

  private val body = HttpEntity(ContentTypes.`application/json`, """{"from":"Tim","text":"Is CI down?"}""")

  "The webhook" should {
    "answer 202 once Dad has saved the message" in {
      val told = mutable.Buffer.empty[(String, Webhook.Message)]
      val route = Webhook.route { (chatId, message) =>
        told += chatId -> message
        Future.successful(Done)
      }
      Post("/chats/%23random/messages", body) ~> route ~> check {
        assert(status == StatusCodes.Accepted)
      }
      assert(told.toSeq == Seq("#random" -> Webhook.Message("Tim", "Is CI down?")))
    }

    "answer 503 when Dad couldn't save it, so the chat sends it again" in {
      val route = Webhook.route((_, _) => Future.failed(RuntimeException("ask timed out")))
      Post("/chats/%23random/messages", body) ~> route ~> check {
        assert(status == StatusCodes.ServiceUnavailable)
      }
    }
  }
