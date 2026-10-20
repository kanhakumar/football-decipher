package smi.generator

import cats.effect.{ExitCode, IO, IOApp}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** CLI: `sbt "generate --seed 42 [--out path]"`. Writes NDJSON envelopes and prints a summary. */
object Generate extends IOApp:
  private final case class Args(seed: Long, out: Option[Path])

  private def parse(args: List[String], acc: Args): Either[String, Args] = args match
    case Nil => Right(acc)
    case "--seed" :: v :: rest =>
      v.toLongOption.toRight(s"bad seed '$v'").flatMap(s => parse(rest, acc.copy(seed = s)))
    case "--out" :: v :: rest => parse(rest, acc.copy(out = Some(Paths.get(v))))
    case other :: _           => Left(s"unknown argument '$other'")

  private val usage = "usage: generate --seed <long> [--out <path>]"

  def run(args: List[String]): IO[ExitCode] =
    parse(args, Args(42, None)) match
      case Left(err) => IO.println(s"$err\n$usage").as(ExitCode(2))
      case Right(a)  =>
        // sbt runs forked from /core, so the default lands in the repo-root out/ folder.
        val path = a.out.getOrElse(Paths.get("..", "out", s"match-${a.seed}.ndjson"))
        val envelopes = MatchGenerator.generate(GeneratorConfig(a.seed))
        val summary = MatchSummary.of(envelopes)
        for
          _ <- IO.blocking {
            Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
            Files.writeString(path, MatchGenerator.toNdjson(envelopes), UTF_8)
          }
          _ <- IO.println(s"wrote ${path.toAbsolutePath.normalize}")
          _ <- IO.println(summary.render(Squads.home.teamId, Squads.away.teamId))
        yield ExitCode.Success
