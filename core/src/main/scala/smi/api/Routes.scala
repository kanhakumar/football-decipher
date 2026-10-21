package smi.api

import cats.effect.IO
import fs2.Stream
import io.circe.{Encoder, Json}
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.jsonEncoder
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import scala.io.Source
import smi.model.*

/** The core HTTP and SSE API (ADR-013). Documented in /openapi.yaml. */
object Routes:
  /** Every path the API serves, as written in openapi.yaml (tested against it). */
  val paths: List[String] = List(
    "/health",
    "/openapi.yaml",
    "/api/match/start",
    "/api/match/pause",
    "/api/match/resume",
    "/api/match/speed",
    "/api/match/state",
    "/api/stream/events",
    "/api/stream/insights",
    "/api/stream/overlays",
    "/api/insights",
    "/api/insights/{insight_id}",
    "/api/overlays",
    "/api/tools/match_state",
    "/api/tools/match_info",
    "/api/tools/team_stats",
    "/api/tools/player_stats",
    "/api/tools/player_workload",
    "/api/tools/team_shape",
    "/api/tools/recent_events",
    "/api/tools/insight/{insight_id}"
  )

  private object Seed extends OptionalQueryParamDecoderMatcher[Long]("seed")
  private object Team extends QueryParamDecoderMatcher[String]("team")
  private object TeamOpt extends OptionalQueryParamDecoderMatcher[String]("team")
  private object PlayerQ extends QueryParamDecoderMatcher[String]("player")
  private object MetricsQ extends OptionalQueryParamDecoderMatcher[String]("metrics")
  private object Period extends OptionalQueryParamDecoderMatcher[Int]("period")
  private object From extends OptionalQueryParamDecoderMatcher[Double]("from_minute")
  private object To extends OptionalQueryParamDecoderMatcher[Double]("to_minute")
  private object Last extends OptionalQueryParamDecoderMatcher[Double]("last_minutes")
  private object N extends OptionalQueryParamDecoderMatcher[Int]("n")
  private object MinImportance extends OptionalQueryParamDecoderMatcher[Double]("min_importance")
  private object Kind extends OptionalQueryParamDecoderMatcher[String]("kind")
  private object All extends OptionalQueryParamDecoderMatcher[Boolean]("all")
  private object FromIndex extends OptionalQueryParamDecoderMatcher[Int]("from")

  private def json[A: Encoder](a: A): IO[Response[IO]] = Ok(a.asJson)
  private def answer[A: Encoder](r: Either[String, A], missing: Boolean = false): IO[Response[IO]] =
    r match
      case Right(a) => json(a)
      case Left(err) => (
        if missing then NotFound(ErrorView(err).asJson) else BadRequest(ErrorView(err).asJson)
      )

  private def list(s: Option[String]): List[String] =
    s.toList.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)

  private lazy val openApi: String =
    Source.fromInputStream(getClass.getResourceAsStream("/openapi.yaml"), UTF_8.name).mkString

  /** Where an SSE stream should start: `?from=`, else after `Last-Event-ID`, else 0. */
  private def startAt(req: Request[IO], from: Option[Int]): Int =
    from
      .orElse(req.headers.get[headers.`Last-Event-Id`].flatMap(_.id.value.toIntOption).map(_ + 1))
      .getOrElse(0)

  def apply(hub: Hub): HttpRoutes[IO] =
    def window(
        period: Option[Int],
        from: Option[Double],
        to: Option[Double],
        last: Option[Double]
    ) =
      Tools.WindowQuery(period, from, to, last)
    HttpRoutes.of[IO] {
      case GET -> Root / "health" => Ok("ok")
      case GET -> Root / "openapi.yaml" =>
        Ok(openApi).map(
          _.withContentType(`Content-Type`(MediaType.unsafeParse("application/yaml")))
        )

      // ---- match control ----
      case req @ POST -> Root / "api" / "match" / "start" :? Seed(seed) =>
        // An empty body means defaults; a body that does not decode is refused, never ignored.
        req.bodyText.compile.string.flatMap { text =>
          val body =
            if text.isBlank then Right(StartRequest())
            else
              io.circe.parser
                .decode[StartRequest](text)
                .left
                .map(e => s"invalid start request: ${e.getMessage}")
          body match
            case Left(err) => BadRequest(ErrorView(err).asJson)
            case Right(r) =>
              val d = MatchConfig()
              val cfg = MatchConfig(
                seed.orElse(r.seed).getOrElse(d.seed),
                r.speed.getOrElse(d.speed),
                r.delayS.getOrElse(d.delayS),
                r.tracking.getOrElse(d.tracking)
              )
              hub.start(cfg).flatMap(res => answer(res.map(Tools.state)))
        }
      case POST -> Root / "api" / "match" / "pause" => hub.pause.flatMap(p => json(Tools.state(p)))
      case POST -> Root / "api" / "match" / "resume" =>
        hub.resume.flatMap(p => json(Tools.state(p)))
      case req @ POST -> Root / "api" / "match" / "speed" =>
        req
          .as[SpeedRequest]
          .flatMap(s => hub.speed(s.speed))
          .flatMap(res => answer(res.map(Tools.state)))
      case GET -> Root / "api" / "match" / "state" =>
        hub.snapshot.flatMap(p => json(Tools.state(p)))

      // ---- streams ----
      case req @ GET -> Root / "api" / "stream" / "events" :? FromIndex(from) =>
        Ok(Streams.events(hub, startAt(req, from)))
      case req @ GET -> Root / "api" / "stream" / "insights" :? FromIndex(from) =>
        Ok(Streams.insights(hub, startAt(req, from)))
      case req @ GET -> Root / "api" / "stream" / "overlays" :? FromIndex(from) =>
        Ok(Streams.overlays(hub, startAt(req, from)))

      // ---- insights and overlays ----
      case GET -> Root / "api" / "insights" :? MinImportance(min) +& Kind(kind) +& All(all) =>
        hub.snapshot.flatMap(p =>
          answer(Tools.insights(p, min.getOrElse(0), kind, all.getOrElse(false)))
        )
      case GET -> Root / "api" / "insights" / id =>
        hub.snapshot.flatMap(p => answer(Tools.insightDetail(p, id), missing = true))
      case req @ POST -> Root / "api" / "overlays" =>
        req.attemptAs[Overlay].value.flatMap {
          case Left(err) => BadRequest(ErrorView(s"invalid overlay: ${err.getMessage}").asJson)
          case Right(o) =>
            hub.addOverlay(o).flatMap {
              case Right(ok) => Accepted(ok.asJson)
              case Left(err) => Conflict(ErrorView(err).asJson)
            }
        }

      // ---- tools (for the MCP server) ----
      case GET -> Root / "api" / "tools" / "match_state" =>
        hub.snapshot.flatMap(p => json(Tools.state(p)))
      case GET -> Root / "api" / "tools" / "match_info" =>
        hub.snapshot.flatMap(p => json(Tools.info(p)))
      case GET -> Root / "api" / "tools" / "team_stats" :? Team(t) +& MetricsQ(ms) +& Period(
            pe
          ) +& From(f) +& To(to) +& Last(l) =>
        hub.snapshot.flatMap(p => answer(Tools.teamStats(p, t, list(ms), window(pe, f, to, l))))
      case GET -> Root / "api" / "tools" / "player_stats" :? PlayerQ(pl) +& MetricsQ(ms) +& Period(
            pe
          ) +& From(f) +& To(to) +& Last(l) =>
        hub.snapshot.flatMap(p => answer(Tools.playerStats(p, pl, list(ms), window(pe, f, to, l))))
      case GET -> Root / "api" / "tools" / "player_workload" :? PlayerQ(pl) +& Period(pe) +& From(
            f
          ) +& To(to) +& Last(l) =>
        hub.snapshot.flatMap(p => answer(Tools.workload(p, pl, window(pe, f, to, l))))
      case GET -> Root / "api" / "tools" / "team_shape" :? Team(t) +& Period(pe) +& From(f) +& To(
            to
          ) +& Last(l) =>
        hub.snapshot.flatMap(p => answer(Tools.shape(p, t, window(pe, f, to, l))))
      case GET -> Root / "api" / "tools" / "recent_events" :? N(n) +& TeamOpt(t) =>
        hub.snapshot.flatMap(p => answer(Tools.recentEvents(p, n.getOrElse(10), t)))
      case GET -> Root / "api" / "tools" / "insight" / id =>
        hub.snapshot.flatMap(p => answer(Tools.insightDetail(p, id), missing = true))
    }

/** Server-sent event streams that replay retained history and then follow the match. */
object Streams:
  /** Follows one match (the current one, or the next to start): emits `items(p)(i)` for every index
    * from `from` up to `ready(p)`, and ends when the match is done or replaced.
    */
  private def follow[A](
      hub: Hub,
      from: Int,
      items: Playback => Vector[A],
      ready: Playback => Int,
      done: (Playback, Int) => Boolean,
      sse: (A, Int) => ServerSentEvent
  ): Stream[IO, ServerSentEvent] =
    hub.state.discrete.dropWhile(_.generation == 0).take(1).flatMap { first =>
      hub.state.discrete
        .takeWhile(_.generation == first.generation)
        .mapAccumulate(from) { (sent, p) =>
          val upTo = math.max(sent, ready(p))
          (upTo, ((sent until upTo).map(i => sse(items(p)(i), i)), done(p, upTo)))
        }
        .takeThrough((_, out) => !out._2)
        .flatMap((_, out) => Stream.emits(out._1))
    }

  private def event(name: String, id: Int, data: Json) =
    ServerSentEvent(
      data = Some(data.noSpaces),
      eventType = Some(name),
      id = Some(ServerSentEvent.EventId(id.toString))
    )

  /** The viewer-delayed feed: envelopes as the viewer should see them. */
  def events(hub: Hub, from: Int): Stream[IO, ServerSentEvent] =
    follow(
      hub,
      from,
      _.feed,
      _.viewer,
      (p, n) => p.status == smi.api.Status.Finished && n >= p.feed.size,
      (e, i) => event(PayloadType.wire(e.payloadType), i, e.asJson)
    )

  /** Insights as the detectors publish them (live side, ahead of the viewer). */
  def insights(hub: Hub, from: Int): Stream[IO, ServerSentEvent] =
    follow(
      hub,
      from,
      _.insights,
      _.insights.size,
      (p, n) => p.status == smi.api.Status.Finished && p.closed && n >= p.insights.size,
      (i, n) => event("insight", n, i.asJson)
    )

  def overlays(hub: Hub, from: Int): Stream[IO, ServerSentEvent] =
    follow(
      hub,
      from,
      _.overlays,
      _.overlays.size,
      (p, n) => p.status == smi.api.Status.Finished && p.closed && n >= p.overlays.size,
      (o, n) => event("overlay", n, o.asJson)
    )
