package smi.generator

import cats.effect.{ExitCode, IO, IOApp}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** CLI: `sbt "calibrate [--seeds 200] [--scenario yaml] [--report path]"`.
  *
  * Measures the scenario's baseline (beats removed) against the target bands, reports the full
  * story scenario next to it, and exits non-zero if any baseline metric is out of range.
  */
object Calibrate extends IOApp:
  private final case class Args(seeds: Int, scenario: Option[Path], report: Option[Path])

  private def parse(args: List[String], acc: Args): Either[String, Args] = args match
    case Nil => Right(acc)
    case "--seeds" :: v :: rest =>
      v.toIntOption
        .filter(_ > 0)
        .toRight(s"bad seed count '$v'")
        .flatMap(n => parse(rest, acc.copy(seeds = n)))
    case "--scenario" :: v :: rest => parse(rest, acc.copy(scenario = Some(Paths.get(v))))
    case "--report" :: v :: rest   => parse(rest, acc.copy(report = Some(Paths.get(v))))
    case other :: _                => Left(s"unknown argument '$other'")

  private val usage = "usage: calibrate [--seeds <n>] [--scenario <yaml>] [--report <md>]"

  def run(args: List[String]): IO[ExitCode] =
    val loaded = for
      a <- parse(args, Args(Calibration.DefaultSeeds, None, None))
      s <- a.scenario.map(Scenario.load).getOrElse(Right(Scenario.default))
    yield (a, s)
    loaded match
      case Left(err) => IO.println(s"$err\n$usage").as(ExitCode(2))
      case Right((a, scenario)) =>
        for
          r <- IO.blocking(Calibration.run(a.seeds, scenario))
          _ <- IO.println(
            s"calibration: ${a.seeds} seeds, scenario ${scenario.name} (baseline is gated)"
          )
          _ <- IO.println(Calibration.table(r))
          _ <- a.report.fold(IO.unit)(p =>
            IO.blocking(Files.writeString(p, Calibration.markdown(r), UTF_8)) *>
              IO.println(s"wrote ${p.toAbsolutePath.normalize}")
          )
          ok = r.outOfRange == 0
          _ <- IO.println(
            if ok then "all baseline metrics in range"
            else s"${r.outOfRange} baseline metric(s) out of range"
          )
        yield if ok then ExitCode.Success else ExitCode.Error
