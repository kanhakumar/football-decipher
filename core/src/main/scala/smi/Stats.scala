package smi

import cats.effect.{ExitCode, IO, IOApp}
import smi.engine.{Engine, Metrics, MetricValue}
import smi.generator.{GeneratorConfig, MatchGenerator}
import smi.model.Basis

/** CLI: `sbt "stats --seed 42"`. Generates a match, runs it through the stats engine and prints
  * team totals with their basis tags. Lives outside `smi.engine` because it uses the generator.
  */
object Stats extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val seed = args match
      case "--seed" :: v :: Nil => v.toLongOption
      case Nil                  => Some(42L)
      case _                    => None
    seed match
      case None => IO.println("usage: stats [--seed <long>]").as(ExitCode(2))
      case Some(s) =>
        val state = Engine.replay(MatchGenerator.generate(GeneratorConfig(s)))
        val teams = state.teams
        def cell(v: MetricValue) =
          v.value.fold("n/a")(x => if x == x.round then f"$x%.0f" else f"$x%.2f")
        val width = Metrics.all.map(_.id.length).max
        val header =
          s"${"metric".padTo(width, ' ')}  ${teams.map(_.value.padTo(8, ' ')).mkString("  ")}  basis"
        val rows = Metrics.all.map { m =>
          val values = teams.map(t => Engine.team(state, m.id, t))
          val basis =
            Basis.wire(values.head.basis) + values.head.via.fold("")(v => s" (fallback: $v)")
          s"${m.id.padTo(width, ' ')}  ${values.map(v => cell(v).padTo(8, ' ')).mkString("  ")}  $basis"
        }
        val d = state.derived
        val score = teams.map(t => d.score.getOrElse(t, 0)).mkString("-")
        IO.println(
          s"seed $s: ${state.events.size} events, score $score\n$header\n${rows.mkString("\n")}"
        ).as(ExitCode.Success)
