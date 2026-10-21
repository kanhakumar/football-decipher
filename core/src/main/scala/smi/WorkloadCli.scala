package smi

import cats.effect.{ExitCode, IO, IOApp}
import smi.engine.{Engine, Window}
import smi.generator.{GeneratorConfig, MatchGenerator}
import smi.model.*
import smi.tracking.{SynthConfig, TrackingSynth}

/** CLI: `sbt "workload --seed 154"`. Generates a match with 5 Hz tracking and prints each player's
  * workload from the engine, with basis tags.
  */
object WorkloadCli extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val seed = args match
      case "--seed" :: v :: Nil => v.toLongOption
      case Nil                  => Some(154L)
      case _                    => None
    seed match
      case None => IO.println("usage: workload [--seed <long>]").as(ExitCode(2))
      case Some(s) =>
        val events = MatchGenerator.generate(GeneratorConfig(s))
        val state = Engine.replay(TrackingSynth.addFrames(events, SynthConfig(s)))
        val end = state.ordered.lastOption
          .map(e => Engine.baseMinute(e.event.period) + e.event.periodClockS / 60)
          .getOrElse(90.0)
        val late = Window(Some(2), end - 15, end + 1)
        val rows = state.lineups.toVector.sortBy(_._1 != state.teams.head).flatMap { (team, l) =>
          (l.starters ++ l.bench).flatMap { p =>
            val d = Engine.player(state, "distance_covered", p.playerId)
            d.value.filter(_ > 0).map { km =>
              def v(id: String, w: Window = Window.Match) = Engine.player(state, id, p.playerId, w)
              val top = v("top_speed").value.fold("n/a")(x => f"${x * 3.6}%.1f")
              val fatigue = v("fatigue_index", late).value.fold("n/a")(x => f"$x%.0f%%")
              f"${team.value}%-6s ${p.shirt}%2d ${p.name}%-18s ${km / 1000}%5.2f km  top $top%5s km/h  sprints ${v("sprints").value.getOrElse(0.0)}%3.0f  late-game fatigue index $fatigue%5s  [${Basis.wire(d.basis)}]"
            }
          }
        }
        IO.println(
          s"seed $s: ${state.workload.frames.values.sum} frames, capability ${state.capability}, coverage ${f"${Engine.coverage(state, Window.Match) * 100}%.1f"}%\n${rows.mkString("\n")}"
        ).as(ExitCode.Success)
