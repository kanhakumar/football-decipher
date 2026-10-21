package smi.api

import cats.data.Kleisli
import cats.effect.IO
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import org.http4s.{HttpApp, Request}
import smi.model.*

/** Where the service writes its log lines: stdout for info, stderr for errors. Tests use `silent`.
  */
final class Log(write: (Boolean, String) => IO[Unit]):
  def info(msg: String): IO[Unit] = write(false, msg)
  def error(msg: String, e: Throwable): IO[Unit] =
    write(true, s"$msg: ${e.getClass.getSimpleName}: ${e.getMessage}")

object Log:
  val silent: Log = Log((_, _) => IO.unit)
  val console: Log = Log { (isError, msg) =>
    IO(LocalTime.now.truncatedTo(ChronoUnit.MILLIS)).flatMap { t =>
      if isError then IO.consoleForIO.errorln(s"$t ERROR $msg")
      else IO.println(s"$t INFO  $msg")
    }
  }

  /** One line per request: method, path, status and time to the response head (for SSE, the time to
    * open the stream). Failures are logged before they reach the server's 500 handler.
    */
  def requests(log: Log)(app: HttpApp[IO]): HttpApp[IO] = Kleisli { (req: Request[IO]) =>
    def line(status: String, ms: Long) = s"${req.method} ${req.uri} -> $status (${ms} ms)"
    IO.monotonic.flatMap { t0 =>
      def elapsed = IO.monotonic.map(t => (t - t0).toMillis)
      app
        .run(req)
        .flatTap(res => elapsed.flatMap(ms => log.info(line(res.status.code.toString, ms))))
        .onError(e => elapsed.flatMap(ms => log.error(line("failed", ms), e)))
    }
  }

  def clock(matchClockS: Double): String =
    val s = matchClockS.toLong
    f"${s / 60}%d:${s % 60}%02d"

  /** What changed between two playback states that is worth a line. */
  def changes(before: Playback, after: Playback): List[String] =
    if before.generation != after.generation then Nil
    else
      val insights = after.insights.drop(before.insights.size).toList.map { i =>
        val status = i.status.map(InsightStatus.wire).getOrElse("active")
        val replaces = i.supersedes.fold("")(s => s" supersedes ${s.value}")
        // The first team is the insight's subject (the scorer's side for a goal).
        val team = i.teams.headOption.fold("-")(_.value)
        f"insight ${i.insightId.value} ${InsightKind.wire(i.kind)} $team" +
          f" at match clock ${clock(i.tMatchS)} importance ${i.importance}%.2f $status$replaces"
      }
      val overlays = after.overlays.drop(before.overlays.size).toList.map { o =>
        s"overlay ${o.overlayId.value} ${OverlayOp.wire(o.op)} ${o.`type`.fold("")(OverlayType.wire)}".trim
      }
      val closed = Option.when(!before.closed && after.closed)(
        s"live side reached the end of the feed (${after.insights.size} insight versions)"
      )
      val finished = Option.when(
        before.status != Status.Finished && after.status == Status.Finished
      ) {
        val score = after.moments.engine.teams
          .map(t => after.moments.engine.derived.score.getOrElse(t, 0))
          .mkString("-")
        s"match finished $score: ${after.feed.size} envelopes, ${after.moments.active.size} active insights, ${after.overlays.size} overlays"
      }
      insights ++ overlays ++ closed ++ finished
