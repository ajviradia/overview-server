package mailers.User

import models.{ConfirmationRequest, OverviewUser}
import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.Play.{start,stop}
import play.api.i18n.Lang
import play.api.test.{FakeApplication,FakeRequest}

class createSpec extends Specification {
  step(start(FakeApplication()))

  trait OurContext extends Scope with Mockito {
    trait UserType extends models.OverviewUser with models.ConfirmationRequest
    val user = mock[UserType]
    user.email returns "email@example.org"
    user.confirmationToken returns "0123456789abcdef"

    val lang = Lang("fu", "BA")
    val request = FakeRequest("POST", "https://example.org/user/create")

    lazy val mailer = create(user)(lang, request)
  }

  "create()" should {
    "send to the user and only the user" in new OurContext {
      mailer.recipients.must(equalTo(Seq(user.email)))
    }

    "include the confirmation URL" in new OurContext {
      mailer.text.must(contain(user.confirmationToken)) 
    }
  }

  step(stop)
}
