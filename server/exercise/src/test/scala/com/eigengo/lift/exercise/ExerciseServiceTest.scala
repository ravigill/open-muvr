package com.eigengo.lift.exercise

import java.text.SimpleDateFormat

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestActor, TestKitBase, TestProbe}
import com.eigengo.lift.Exercise._
import com.eigengo.lift.common.UserId
import com.eigengo.lift.exercise.UserExercisesProcessor._
import com.eigengo.lift.exercise.UserExercisesSessions._
import com.eigengo.lift.exercise.UserExercisesStatistics.UserGetExerciseSuggestions
import org.scalatest.{FlatSpec, Matchers}
import spray.http.{ContentTypes, HttpEntity}
import spray.testkit.ScalatestRouteTest

import scalaz._

object ExerciseServiceTest {

  object TestData {
    private val dateFormat = new SimpleDateFormat("yyyy-MM-dd")

    val userId = UserId.randomId()
    val sessionId = SessionId.randomId()

    val squatName = "squat"
    val squat = Exercise(squatName, Some(1.0), None)
    val intensity = Some(1.0)
    val startDate = dateFormat.parse("1970-01-01")
    val endDate = dateFormat.parse("1970-01-01")
    val sessionProps = SessionProperties(startDate, Seq("Legs"), 1.0)
    val muscleGroups = List(MuscleGroup("legs", "Legs", List("squat")))
    val sessionSummary = List(SessionSummary(sessionId, sessionProps, Array(1.0)))
    val session = Some(ExerciseSession(sessionId, sessionProps, List(ExerciseSet(List(squat)))))
    val sessionDates = List(SessionDate(startDate, List(SessionIntensity(intensity.get, intensity.get))))
    val multiPacket: Array[Byte] = Array(
      0xca, 0xb1, 0x02, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x04, 0x01, 0xff, 0x01, 0x02, 0x03,
      0x00, 0x02, 0x02, 0xf0, 0x01).map(_.toByte)
    val emptyResponse = "{}"

    val suggestionsEntity = HttpEntity(ContentTypes.`application/json`,
      """
        |[
        |   { "date":"2015-02-20",
        |     "source":"history",
        |     "muscleGroupKeys": ["arms"],
        |     "intensity": 1.0
        |   },
        |   { "date":"2015-02-21",
        |     "source":"programme"
        |   },
        |   { "date":"2015-02-22",
        |     "source": { "notes":"Chop, chop" },
        |     "muscleGroupKeys": ["legs"],
        |     "intensity":0.8
        |   }
        |]
      """.stripMargin)

    val suggestions = Suggestions(
      Suggestion.Session(dateFormat.parse("2015-02-20"), SuggestionSource.History, List("arms"), 1.0) ::
        Suggestion.Rest(dateFormat.parse("2015-02-21"), SuggestionSource.Programme) ::
        Suggestion.Session(dateFormat.parse("2015-02-22"), SuggestionSource.Trainer("Chop, chop"), List("legs"), 0.8) :: Nil
    )
  }

  def probe(implicit system: ActorSystem) = {
    val probe = TestProbe()

    probe.setAutoPilot {
      new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case UserExerciseSessionStart(_, _) ⇒
            sender ! \/.right(TestData.userId.id)
            TestActor.KeepRunning
          case UserGetExerciseSessionsSummary(_, _, _) ⇒
            sender ! TestData.sessionSummary
            TestActor.KeepRunning
          case UserGetExerciseSession(_, _) ⇒
            sender ! TestData.session
            TestActor.KeepRunning
          case UserExerciseDataProcessMultiPacket(_, _, _) ⇒
            sender ! \/.right(())
            TestActor.KeepRunning
          case UserGetExerciseSessionsDates(_) ⇒
            sender ! TestData.sessionDates
            TestActor.KeepRunning
          case UserExerciseSessionEnd(_, _) ⇒
            sender ! \/.right(())
            TestActor.KeepRunning
          case UserExerciseExplicitClassificationStart(_, _, _) ⇒
	    sender ! List(TestData.squatName)

            TestActor.KeepRunning
          case UserExerciseSetSuggestions(_, _) ⇒
            sender ! \/.right(())
            TestActor.KeepRunning
          case UserGetExerciseSuggestions(_) ⇒
            sender ! TestData.suggestions
            TestActor.KeepRunning
        }
      }
    }

    probe
  }
}

class ExerciseServiceTest
  extends FlatSpec
  with ScalatestRouteTest
  with TestKitBase
  with Matchers
  with ExerciseService
  with ExerciseMarshallers
  with ImplicitSender {

  import com.eigengo.lift.exercise.ExerciseServiceTest._

  val probe = ExerciseServiceTest.probe

  val underTest = exerciseRoute(probe.ref, probe.ref, probe.ref)

  val dateFormat = new SimpleDateFormat("yyyy-MM-dd")

  "The Exercise service" should "listen at GET /exercise/musclegroups endpoint" in {
    Get("/exercise/musclegroups") ~> underTest ~> check {
      responseAs[List[MuscleGroup]] should be(UserExercisesStatistics.supportedMuscleGroups)
    }
  }

  it should "listen at POST exercise/:UserIdValue/start endpoint" in {
    Post(s"/exercise/${TestData.userId.id}/start", TestData.sessionProps) ~> underTest ~> check {
      UserId(response.entity.asString.replace("\"", "")) should be(TestData.userId)
    }

    probe.expectMsg(UserExerciseSessionStart(TestData.userId, TestData.sessionProps))
  }

  it should "listen at GET exercise/:UserIdValue?startDate=date&endDate=date endpoint" in {
    Get(s"/exercise/${TestData.userId.id}?startDate=${dateFormat.format(TestData.startDate)}&endDate=${dateFormat.format(TestData.endDate)}") ~> underTest ~> check {
      responseAs[List[SessionSummary]].head.sessionProps should be(TestData.sessionSummary.head.sessionProps)
    }

    probe.expectMsg(UserGetExerciseSessionsSummary(TestData.userId, TestData.startDate, TestData.endDate))
  }

  it should "listen at GET exercise/:UserIdValue?date=date endpoint" in {
    Get(s"/exercise/${TestData.userId.id}?date=${dateFormat.format(TestData.startDate)}") ~> underTest ~> check {
      responseAs[List[SessionSummary]].head.sessionProps should be(TestData.sessionSummary.head.sessionProps)
    }

    probe.expectMsg(UserGetExerciseSessionsSummary(TestData.userId, TestData.startDate, TestData.startDate))
  }

  it should "listen at GET exercise/:UserIdValue endpoint" in {
    Get(s"/exercise/${TestData.userId.id}") ~> underTest ~> check {
      responseAs[List[SessionDate]] should be(TestData.sessionDates)
    }

    probe.expectMsg(UserGetExerciseSessionsDates(TestData.userId))
  }

  it should "listen at GET exercise/:UserIdValue/:SessionIdValue endpoint" in {
    Get(s"/exercise/${TestData.userId.id}/${TestData.sessionId.id}") ~> underTest ~> check {
      responseAs[Option[ExerciseSession]] should be(TestData.session)
    }

    probe.expectMsg(UserGetExerciseSession(TestData.userId, TestData.sessionId))
  }

  it should "listen at PUT exercise/:UserIdValue/:SessionIdValue endpoint" in {
    Put(s"/exercise/${TestData.userId.id}/${TestData.sessionId.id}").withEntity(HttpEntity(bytes = TestData.multiPacket)) ~> underTest ~> check {
      response.entity.asString should be(TestData.emptyResponse)
    }

    val UserExerciseDataProcessMultiPacket(TestData.userId, TestData.sessionId, mp) = probe.expectMsgType[UserExerciseDataProcessMultiPacket]
    mp.packets.size should be(2)
    mp.packets(0).sourceLocation should be(SensorDataSourceLocationWrist)
    mp.packets(0).payload.getByte(0) should be(0xff.toByte)
    mp.packets(1).sourceLocation should be(SensorDataSourceLocationWaist)
  }

  it should "listen at POST exercise/:UserIdValue/suggestions endpoint" in {
    Post(s"/exercise/${TestData.userId.id}/suggestions").withEntity(TestData.suggestionsEntity) ~> underTest ~> check {
      response.entity.asString should be(TestData.emptyResponse)
    }

    probe.expectMsg(UserExerciseSetSuggestions(TestData.userId, TestData.suggestions))
  }

  it should "listen at GET exercise/:UserIdValue/suggestions endpoint" in {
    Get(s"/exercise/${TestData.userId.id}/suggestions") ~> underTest ~> check {
      response.entity.asString.replaceAll("\\W","") should be(TestData.suggestionsEntity.asString.replaceAll("\\W",""))
    }

    probe.expectMsg(UserGetExerciseSuggestions(TestData.userId))
  }

  it should "listen at POST exercise/:UserIdValue/:SessionIdValue/end endpoint" in {
    Post(s"/exercise/${TestData.userId.id}/${TestData.sessionId.id}/end") ~> underTest ~> check {
      response.entity.asString should be(TestData.emptyResponse)
    }

    probe.expectMsg(UserExerciseSessionEnd(TestData.userId, TestData.sessionId))
  }

  it should "listen at POST exercise/:UserIdValue/:SessionIdValue/classification endpoint" in {
    Post(s"/exercise/${TestData.userId.id}/${TestData.sessionId.id}/classification", TestData.squat) ~> underTest ~> check {
      response.entity.asString should be(TestData.emptyResponse)
    }

    probe.expectMsg(UserExerciseExplicitClassificationStart(TestData.userId, TestData.sessionId, TestData.squat))
  }

}
