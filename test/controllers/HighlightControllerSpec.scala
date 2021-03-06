package controllers

import org.specs2.specification.Scope
import scala.concurrent.Future

import controllers.backend.HighlightBackend
import org.overviewproject.searchindex.Highlight

class HighlightControllerSpec extends ControllerSpecification {
  trait HighlightScope extends Scope {
    val mockHighlightBackend = mock[HighlightBackend]

    val controller = new HighlightController {
      override val highlightBackend = mockHighlightBackend
    }

    def foundHighlights: Seq[Highlight] = Seq()

    mockHighlightBackend.index(any[Long], any[Long], any[String]) returns Future { foundHighlights }
  }

  "#index" should {
    trait IndexScope extends HighlightScope {
      val request = fakeAuthorizedRequest
      lazy val result = controller.index(1L, 2L, "foo")(request)
    }

    "return a JSON response with Highlights" in new IndexScope {
      override def foundHighlights = Seq(Highlight(2, 4), Highlight(6, 8))
      h.status(result) must beEqualTo(h.OK)
      h.contentAsString(result) must beEqualTo("[[2,4],[6,8]]")
    }

    "return an empty response" in new IndexScope {
      override def foundHighlights = Seq()
      h.status(result) must beEqualTo(h.OK)
      h.contentAsString(result) must beEqualTo("[]")
    }

    "call HighlightBackend.index" in new IndexScope {
      h.status(result)
      there was one(mockHighlightBackend).index(1L, 2L, "foo")
    }
  }
}
