package smi

import cats.effect.{IO, Ref}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import smi.api.{Auth, Log, Settings, SettingsHub, SettingsSource}

/** Settings from config/app.yaml with App Configuration values over them (ADR-019). */
class SettingsSuite extends CatsEffectSuite:
  test("every setting has a valid default in config/app.yaml, and the file has nothing else") {
    val d = Settings.default
    assertEquals(d.problems, Nil)
    assertEquals(Settings.defaultText.keySet, Settings.Spec.map(_._1).toSet)
    assertEquals(d.num("moments:model_min_importance"), 0.5)
    assertEquals(d.signInRequired, true)
    assert(d.sources.values.forall(_ == Settings.Default))
  }

  test("store values win; bad, unknown and rule-breaking ones are ignored and reported") {
    val s = Settings.resolve(
      Map(
        "match:speed" -> "20",
        "access:sign_in_required" -> "false",
        "narration:reasoning_effort" -> "high",
        "moments:model_slots" -> "9",
        "match:colour" -> "red",
        "moments:template_min_importance" -> "0.8"
      )
    )
    assertEquals(s.num("match:speed"), 20.0)
    assertEquals(s.signInRequired, false)
    assertEquals(s.sources("match:speed"), Settings.Store)
    assertEquals(s.text("narration:reasoning_effort"), "minimal")
    assertEquals(s.num("moments:model_slots"), 2.0)
    assertEquals(s.num("moments:template_min_importance"), 0.35, "0.8 is above the model cut")
    assertEquals(s.problems.size, 4, s.problems.mkString("; "))
    assert(s.problems.exists(_.startsWith("match:colour")))
  }

  test("CORS origins parse, and /api/config shows values, sources and problems") {
    val s = Settings.resolve(Map("access:cors_origins" -> "https://a.example, https://b.example"))
    assertEquals(s.corsOrigins, List("https://a.example", "https://b.example"))
    val j = s.asJson.hcursor
    assertEquals(j.downField("sources").get[String]("access:cors_origins"), Right(Settings.Store))
    assertEquals(j.downField("settings").get[Double]("match:speed"), Right(10.0))
  }

  test("the hub reloads from its source and keeps the last settings if the source fails") {
    for
      fail <- Ref.of[IO, Boolean](false)
      source = new SettingsSource:
        val name = "test"
        def read =
          fail.get.flatMap(f =>
            if f then IO.raiseError(RuntimeException("down"))
            else IO.pure(Map("match:seed" -> "42"))
          )
      hub <- SettingsHub(Some(source), Log.silent)
      before <- hub.get
      after <- hub.reload
      _ <- fail.set(true)
      kept <- hub.reload
    yield
      assertEquals(before.num("match:seed"), 154.0)
      assertEquals(after.num("match:seed"), 42.0)
      assertEquals(kept.num("match:seed"), 42.0)
  }

  test("with sign-in not required the gate lets anyone in") {
    val app =
      Auth(Some("k"), None, IO.pure(false))(HttpRoutes.of[IO] { case _ => Ok("in") }.orNotFound)
    app.run(Request[IO](uri = uri"/api/match/state")).map(r => assertEquals(r.status, Status.Ok))
  }

  test("/api/config is public and lists every setting") {
    val routes = smi.api.Routes(null, SettingsHub.static).orNotFound
    routes.run(Request[IO](uri = uri"/api/config")).flatMap(_.as[String]).map { body =>
      val j = io.circe.parser.parse(body).getOrElse(Json.Null)
      assertEquals(j.hcursor.downField("settings").keys.map(_.size), Some(Settings.Spec.size))
      assert(Auth.Public("/api/config"))
    }
  }
