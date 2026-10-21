package smi

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.yaml.v12.parser as yaml
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Last-Event-Id`
import org.http4s.implicits.*
import scala.concurrent.duration.*
import smi.api.{Hub, MatchConfig, Routes}
import smi.insight.Moments
import smi.model.*

/** A test client driving the core API in memory: control, streams, tools, overlays. */
class ApiSuite extends CatsEffectSuite:
  override val munitIOTimeout = 3.minutes

  private val hubs = ResourceSuiteLocalFixture("hub", Hub.resource())
  override def munitFixtures = List(hubs)

  private def app = Routes(hubs()).orNotFound
  private def call(
      method: Method,
      uri: String,
      body: Option[Json] = None,
      hs: Header.ToRaw*
  ): IO[(Status, String)] =
    val req = Request[IO](method, Uri.unsafeFromString(uri)).putHeaders(hs*)
    app.run(body.fold(req)(b => req.withEntity(b))).flatMap(r => r.as[String].map(r.status -> _))
  private def get(uri: String, hs: Header.ToRaw*) = call(Method.GET, uri, None, hs*)
  private def post(uri: String, body: Json = Json.obj()) = call(Method.POST, uri, Some(body))
  private def json(s: String): Json = parse(s).fold(throw _, identity)
  private def field(s: String, path: String*): Json =
    path.foldLeft(json(s).hcursor: io.circe.ACursor)(_.downField(_)).focus.getOrElse(Json.Null)

  /** SSE body -> data payloads and ids. */
  private def frames(body: String): Vector[(Int, Json)] =
    body.split("\n\n").toVector.filter(_.contains("data:")).map { block =>
      val lines = block.linesIterator.toVector
      val id = lines.collectFirst { case l if l.startsWith("id:") => l.drop(3).trim.toInt }.get
      id -> json(lines.collectFirst { case l if l.startsWith("data:") => l.drop(5).trim }.get)
    }

  private def start(
      seed: Long = 154,
      speed: Double = 600,
      delay: Double = 10,
      tracking: Boolean = false
  ) =
    post(
      "/api/match/start",
      Json.obj(
        "seed" -> seed.asJson,
        "speed" -> speed.asJson,
        "delay_s" -> delay.asJson,
        "tracking" -> tracking.asJson
      )
    )

  private def untilFinished: IO[Unit] =
    get("/api/match/state").flatMap { (_, s) =>
      if field(s, "status").asString.contains("finished") then IO.unit
      else IO.sleep(200.millis) >> untilFinished
    }

  test("a test client drives a full replay: every envelope, insight and overlay arrives in order") {
    val expectedFeed = Hub.feed(MatchConfig(154))
    val expectedInsights = Moments.run(expectedFeed).out
    for
      (status, _) <- start()
      _ = assertEquals(status, Status.Ok)
      events <- get("/api/stream/events").map(r => frames(r._2))
      insights <- get("/api/stream/insights").map(r => frames(r._2))
      overlays <- get("/api/stream/overlays").map(r => frames(r._2))
      (_, state) <- get("/api/match/state")
    yield
      assertEquals(events.map(_._1), expectedFeed.indices.toVector)
      assertEquals(events.map(_._2), expectedFeed.map(_.asJson))
      assertEquals(
        insights.map(_._2),
        expectedInsights.map(_.asJson),
        "same insights as an offline run"
      )
      val goals = expectedInsights.count(i =>
        i.kind == InsightKind.Goal && i.status.contains(InsightStatus.Active)
      )
      assertEquals(
        overlays.count(_._2.hcursor.get[String]("type").contains("scoreline_update")),
        goals
      )
      assertEquals(field(state, "status").asString, Some("finished"))
      assertEquals(field(state, "score"), Json.obj("T_HOM" -> 2.asJson, "T_AWY" -> 2.asJson))
  }

  test("a stream resumes after Last-Event-ID") {
    for
      _ <- start(speed = 600)
      _ <- untilFinished
      (_, body) <- get("/api/stream/events", `Last-Event-Id`(ServerSentEvent.EventId("1600")))
      (_, fromParam) <- get("/api/stream/events?from=1660")
    yield
      val ids = frames(body).map(_._1)
      assertEquals(ids.head, 1601)
      assertEquals(ids.last, Hub.feed(MatchConfig(154)).size - 1)
      assertEquals(frames(fromParam).map(_._1).head, 1660)
  }

  test("the viewer runs behind the live side by the configured delay; pause and speed work") {
    for
      _ <- start(speed = 60, delay = 10)
      _ <- IO.sleep(3.seconds)
      (_, running) <- get("/api/match/state")
      _ <- post("/api/match/pause")
      (_, paused1) <- get("/api/match/state")
      _ <- IO.sleep(500.millis)
      (_, paused2) <- get("/api/match/state")
      (badStatus, _) <- post("/api/match/speed", Json.obj("speed" -> 1000.asJson))
      (okStatus, faster) <- post("/api/match/speed", Json.obj("speed" -> 40.asJson))
      _ <- post("/api/match/resume")
    yield
      def clock(s: String, side: String) = field(s, side, "match_clock_s").asNumber.get.toDouble
      val gap = clock(running, "live") - clock(running, "viewer")
      // Both clocks are the last event each side has seen, so the gap is the 10 s delay give or
      // take the spacing between events (a few seconds in quiet spells).
      assert(gap >= 6 && gap <= 16, s"live is $gap s ahead of the viewer")
      assertEquals(field(paused1, "status").asString, Some("paused"))
      assertEquals(
        field(paused1, "envelopes_live"),
        field(paused2, "envelopes_live"),
        "nothing moves while paused"
      )
      assertEquals(badStatus, Status.BadRequest)
      assertEquals(
        (okStatus, field(faster, "speed").asNumber.map(_.toDouble)),
        (Status.Ok, Some(40.0))
      )
  }

  test("tool endpoints answer small, tagged facts and refuse bad requests") {
    for
      _ <- start(speed = 600)
      _ <- untilFinished
      (s1, team) <- get("/api/tools/team_stats?team=T_HOM&metrics=goals,xg,ppda")
      (s2, _) <- get("/api/tools/team_stats?team=T_NOPE")
      (s3, _) <- get("/api/tools/player_stats?player=P_HOM_09&metrics=possession_share")
      (s4, player) <- get(
        "/api/tools/player_stats?player=P_HOM_09&period=2&from_minute=60&to_minute=90"
      )
      (s5, recent) <- get("/api/tools/recent_events?n=500")
      (s6, shape) <- get("/api/tools/team_shape?team=T_AWY&last_minutes=15")
      (s7, workload) <- get("/api/tools/player_workload?player=P_HOM_02")
      (s8, _) <- get("/api/tools/insight/I_9999")
      (s9, list) <- get("/api/insights?min_importance=0.85")
      (_, info) <- get("/api/tools/match_info")
    yield
      val squads = field(info, "teams").asArray.get
      assertEquals(
        squads.flatMap(_.hcursor.get[String]("name").toOption),
        Vector("Northgate FC", "Eastmere Rovers")
      )
      assertEquals(
        squads.map(_.hcursor.downField("starters").values.map(_.size)),
        Vector(Some(11), Some(11))
      )
      assertEquals(
        List(s1, s2, s3, s4, s5, s6, s7, s8, s9),
        List(
          Status.Ok,
          Status.BadRequest,
          Status.BadRequest,
          Status.Ok,
          Status.Ok,
          Status.Ok,
          Status.Ok,
          Status.NotFound,
          Status.Ok
        )
      )
      val metrics = field(team, "metrics").asArray.get
      assertEquals(
        metrics.flatMap(_.hcursor.get[String]("basis").toOption).distinct,
        Vector("event_data")
      )
      assertEquals(metrics.head.hcursor.get[Double]("value"), Right(2.0), "two home goals")
      assertEquals(field(player, "window", "from_minute").asNumber.map(_.toDouble), Some(60.0))
      assertEquals(field(recent, "count").asNumber.flatMap(_.toInt), Some(50), "capped at 50")
      assertEquals(
        field(shape, "basis").asString,
        Some("event_estimate"),
        "no tracking: shape is estimated from events"
      )
      val distance = field(workload, "metrics").asArray.get.head
      assertEquals(distance.hcursor.get[String]("basis"), Right("event_estimate"))
      assert(
        field(list, "insights").asArray.get
          .forall(_.hcursor.get[Double]("importance").exists(_ >= 0.85))
      )
  }

  test("with tracking on, workload and shape answer from tracking") {
    for
      _ <- start(speed = 600, tracking = true)
      _ <- untilFinished
      (_, workload) <- get("/api/tools/player_workload?player=P_HOM_02")
      (_, shape) <- get("/api/tools/team_shape?team=T_HOM&period=1&from_minute=10&to_minute=20")
    yield
      val distance = field(workload, "metrics").asArray.get.head
      assertEquals(distance.hcursor.get[String]("basis"), Right("tracking"))
      assert(distance.hcursor.get[Double]("value").exists(_ > 9000), distance.noSpaces)
      assertEquals(field(shape, "basis").asString, Some("tracking"))
      assertEquals(field(shape, "players").asArray.map(_.size), Some(11))
  }

  test("overlays from the agent service are validated, published once, and streamed") {
    val good = json(SchemaFiles.readString("examples/overlay.example.json"))
      .mapObject(_.add("match_id", "SYN-154".asJson).add("overlay_id", "O_00000001".asJson))
    for
      _ <- start(speed = 600)
      _ <- untilFinished
      (ok, _) <- post("/api/overlays", good)
      (dup, _) <- post("/api/overlays", good)
      (bad, _) <- post("/api/overlays", good.mapObject(_.add("op", "hide".asJson)))
      (other, _) <- post(
        "/api/overlays",
        good.mapObject(_.add("match_id", "SYN-1".asJson).add("overlay_id", "O_00000002".asJson))
      )
      (_, stream) <- get("/api/stream/overlays")
    yield
      assertEquals(
        List(ok, dup, bad, other),
        List(Status.Accepted, Status.Conflict, Status.BadRequest, Status.Conflict)
      )
      assert(frames(stream).exists(_._2.hcursor.get[String]("overlay_id").contains("O_00000001")))
  }

  test("starting a new match ends the streams of the previous one") {
    for
      _ <- start(speed = 1)
      fiber <- get("/api/stream/events").start
      _ <- IO.sleep(500.millis)
      _ <- start(speed = 600)
      (_, body) <- fiber.joinWithNever
    yield assert(frames(body).size < 10, s"${frames(body).size} events from the abandoned match")
  }

  test("a start body that does not decode is refused, not replaced by defaults") {
    val req = Request[IO](Method.POST, uri"/api/match/start").withEntity("{seed:154,speed:20}")
    for
      res <- app.run(req)
      (unknown, _) <- post("/api/match/start", Json.obj("sped" -> 20.asJson))
      (empty, state) <- call(Method.POST, "/api/match/start?seed=7")
    yield
      assertEquals((res.status, unknown, empty), (Status.BadRequest, Status.BadRequest, Status.Ok))
      assertEquals(field(state, "seed").asNumber.flatMap(_.toLong), Some(7L))
  }

  test("the service logs requests and the match lifecycle") {
    IO.ref(Vector.empty[String]).flatMap { lines =>
      val log = smi.api.Log((_, msg) => lines.update(_ :+ msg))
      Hub.resource(log).use { hub =>
        val logged = smi.api.Log.requests(log)(Routes(hub).orNotFound)
        def send(method: Method, uri: String, body: Json = Json.obj()) =
          logged.run(Request[IO](method, Uri.unsafeFromString(uri)).withEntity(body)).void
        def finished: IO[Unit] = hub.snapshot.flatMap(p =>
          if p.status == smi.api.Status.Finished then IO.unit else IO.sleep(200.millis) >> finished
        )
        for
          _ <- send(Method.POST, "/api/match/start", Json.obj("speed" -> 600.asJson))
          _ <- finished
          _ <- send(Method.GET, "/api/tools/team_stats?team=T_NOPE")
          out <- lines.get
        yield
          def has(s: String) =
            assert(out.exists(_.contains(s)), s"no line with '$s' in\n${out.mkString("\n")}")
          has("match 1 started: seed 154, speed 600.0x")
          has("POST /api/match/start -> 200")
          has("insight I_")
          has("overlay O_90000001 show scoreline_update")
          has("match finished 2-2")
          has("GET /api/tools/team_stats?team=T_NOPE -> 400")
      }
    }
  }

  test("every route is documented in openapi.yaml") {
    for (status, body) <- get("/openapi.yaml")
    yield
      assertEquals(status, Status.Ok)
      val documented =
        yaml.parse(body).toOption.flatMap(_.hcursor.downField("paths").keys).get.toSet
      assertEquals(documented, Routes.paths.toSet)
  }
