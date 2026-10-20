package smi.generator

import smi.model.*

/** A goal or red card with its display minute, for checking story beats. */
final case class KeyMoment(team: TeamId, minute: Double, situation: Option[ShotSituation])

/** Headline numbers for one generated match, used for plausibility checks and calibration. */
final case class MatchSummary(
    envelopes: Int,
    events: Int,
    byType: Map[EventType, Int],
    passes: Map[TeamId, Int],
    completedPasses: Map[TeamId, Int],
    shots: Map[TeamId, Int],
    goals: Vector[KeyMoment],
    redCards: Vector[KeyMoment],
    yellowCards: Int,
    fouls: Int,
    durationMinutes: Double
):
  def totalPasses: Int = passes.values.sum
  def totalShots: Int = shots.values.sum
  def totalGoals: Int = goals.size
  def passCompletion: Double = completedPasses.values.sum.toDouble / math.max(1, totalPasses)
  def goalsFor(team: TeamId): Int = goals.count(_.team == team)

  /** Share of passes, a simple possession proxy. */
  def possessionShare(team: TeamId): Double =
    passes.getOrElse(team, 0).toDouble / math.max(1, totalPasses)

  def render(home: TeamId, away: TeamId): String =
    def side(m: Map[TeamId, Int]) = s"${m.getOrElse(home, 0)}-${m.getOrElse(away, 0)}"
    def moments(ms: Vector[KeyMoment]) =
      if ms.isEmpty then "none"
      else
        ms.map(m =>
          f"${m.team.value} ${m.minute}%.0f'" + m.situation.fold("")(s =>
            s" (${ShotSituation.wire(s)})"
          )
        ).mkString(", ")
    val completion = passCompletion * 100
    val (homeShare, awayShare) = (possessionShare(home) * 100, possessionShare(away) * 100)
    val types =
      byType.toVector.sortBy(-_._2).map((t, n) => s"${EventType.wire(t)} $n").mkString(", ")
    List(
      s"score ${goalsFor(home)}-${goalsFor(away)}  shots ${side(shots)}  passes ${side(passes)}",
      f"pass completion $completion%.1f%%  possession $homeShare%.1f%% / $awayShare%.1f%%",
      s"goals: ${moments(goals)}",
      s"fouls $fouls  yellow $yellowCards  red: ${moments(redCards)}",
      f"events $events  envelopes $envelopes  match clock $durationMinutes%.1f min",
      s"by type: $types"
    ).mkString("\n")

object MatchSummary:
  def events(envelopes: Vector[Envelope]): Vector[Event] =
    envelopes.collect { case Envelope(_, _, _, _, Op.Create, _, _, Payload.Ev(e)) => e }

  def minuteOf(e: Event): Double = Scenario.displayMinute(e.period, e.periodClockS)

  def of(envelopes: Vector[Envelope]): MatchSummary =
    val evs = events(envelopes)
    def count(p: Event => Boolean) = evs.filter(p).groupMapReduce(_.teamId)(_ => 1)(_ + _)
    def isPass(e: Event) = e.`type` == EventType.Pass
    val cards = evs.flatMap(e => e.details.collect { case f: FoulDetails => e -> f.card })
    MatchSummary(
      envelopes = envelopes.size,
      events = evs.size,
      byType = evs.groupMapReduce(_.`type`)(_ => 1)(_ + _),
      passes = count(isPass),
      completedPasses = count(e => isPass(e) && e.outcome.contains(Outcome.Complete)),
      shots = count(_.`type` == EventType.Shot),
      goals = evs.collect {
        case e if e.outcome.contains(Outcome.Goal) =>
          KeyMoment(e.teamId, minuteOf(e), e.details.collect { case s: ShotDetails => s.situation })
      },
      redCards = cards.collect { case (e, Card.Red) => KeyMoment(e.teamId, minuteOf(e), None) },
      yellowCards = cards.count(_._2 == Card.Yellow),
      fouls = cards.size,
      durationMinutes = evs.lastOption.map(_.matchClockS / 60).getOrElse(0.0)
    )
