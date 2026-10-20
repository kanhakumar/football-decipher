package smi.generator

import smi.model.*

/** Headline numbers for one generated match, used for plausibility checks and calibration. */
final case class MatchSummary(
    envelopes: Int,
    events: Int,
    passes: Map[TeamId, Int],
    completedPasses: Map[TeamId, Int],
    shots: Map[TeamId, Int],
    goals: Map[TeamId, Int],
    durationMinutes: Double
):
  def totalPasses: Int = passes.values.sum
  def totalShots: Int = shots.values.sum
  def totalGoals: Int = goals.values.sum
  def passCompletion: Double = completedPasses.values.sum.toDouble / math.max(1, totalPasses)

  /** Share of passes, a simple possession proxy. */
  def possessionShare(team: TeamId): Double =
    passes.getOrElse(team, 0).toDouble / math.max(1, totalPasses)

  def render(home: TeamId, away: TeamId): String =
    def side(m: Map[TeamId, Int]) = s"${m.getOrElse(home, 0)}-${m.getOrElse(away, 0)}"
    val completion = passCompletion * 100
    val (homeShare, awayShare) = (possessionShare(home) * 100, possessionShare(away) * 100)
    List(
      s"score ${side(goals)}  shots ${side(shots)}  passes ${side(passes)}",
      f"pass completion $completion%.1f%%  possession $homeShare%.1f%% / $awayShare%.1f%%",
      f"events $events  envelopes $envelopes  match clock $durationMinutes%.1f min"
    ).mkString("\n")

object MatchSummary:
  def of(envelopes: Vector[Envelope]): MatchSummary =
    val events = envelopes.collect { case Envelope(_, _, _, _, Op.Create, _, _, Payload.Ev(e)) =>
      e
    }
    def count(p: Event => Boolean) = events.filter(p).groupMapReduce(_.teamId)(_ => 1)(_ + _)
    MatchSummary(
      envelopes = envelopes.size,
      events = events.size,
      passes = count(_.`type` == EventType.Pass),
      completedPasses =
        count(e => e.`type` == EventType.Pass && e.outcome.contains(Outcome.Complete)),
      shots = count(_.`type` == EventType.Shot),
      goals = count(e => e.`type` == EventType.Shot && e.outcome.contains(Outcome.Goal)),
      durationMinutes = events.lastOption.map(_.matchClockS / 60).getOrElse(0.0)
    )
