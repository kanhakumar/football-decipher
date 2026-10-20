package smi

import cats.effect.{ExitCode, IO, IOApp}
import smi.generator.{GeneratorConfig, MatchGenerator}
import smi.insight.Moments
import smi.model.*

/** CLI: `sbt "insights --seed 42 [--top 15]"`. Ranked insights for a generated match. Lives outside
  * `smi.insight` because it uses the generator.
  */
object Insights extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    def parse(rest: List[String], seed: Long, top: Int): Option[(Long, Int)] = rest match
      case Nil                   => Some((seed, top))
      case "--seed" :: v :: tail => v.toLongOption.flatMap(parse(tail, _, top))
      case "--top" :: v :: tail  => v.toIntOption.flatMap(parse(tail, seed, _))
      case _                     => None
    parse(args, 42, 15) match
      case None => IO.println("usage: insights [--seed <long>] [--top <n>]").as(ExitCode(2))
      case Some((seed, top)) =>
        val state = Moments.run(MatchGenerator.generate(GeneratorConfig(seed)))
        val ranked = state.ranked
        val short = state.engine.lineups.map((t, l) => t -> l.short)
        def line(i: Insight) =
          val minute = i
            .facts("minute")
            .flatMap(_.asNumber)
            .flatMap(_.toInt)
            .orElse(i.facts("after_to_minute").flatMap(_.asNumber).flatMap(_.toInt))
            .getOrElse(0)
          val team = i.teams.headOption.map(t => short.getOrElse(t, t.value)).getOrElse("")
          val params = i.whyItMatters.params.toList.map((k, v) => s"$k=${v.noSpaces}").mkString(" ")
          f"$minute%3d'  ${i.importance}%.2f  ${InsightKind.wire(i.kind)}%-17s $team%-4s ${i.whyItMatters.code.value}%-31s $params"
        val counts =
          ranked.groupMapReduce(i => InsightKind.wire(i.kind))(_ => 1)(_ + _).toList.sortBy(-_._2)
        val d = state.engine.derived
        val score = state.engine.teams.map(t => d.score.getOrElse(t, 0)).mkString("-")
        IO.println(
          (List(
            s"seed $seed: score $score, ${ranked.size} insights (${counts.map((k, n) => s"$k $n").mkString(", ")})",
            s"top $top by importance:"
          ) ++ ranked.take(top).map(line) ++
            List("", "in match order:") ++ ranked.sortBy(_.tMatchS).map(line)).mkString("\n")
        ).as(ExitCode.Success)
