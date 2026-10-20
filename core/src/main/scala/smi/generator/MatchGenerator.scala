package smi.generator

import io.circe.syntax.*
import java.time.Instant
import scala.collection.mutable
import smi.model.*

final case class GeneratorConfig(
    seed: Long,
    scenario: Scenario = Scenario.default,
    kickoff: Instant = Instant.parse("2026-10-18T15:00:00Z"),
    /** Broadcast pre-roll: video_ts_ms = wall time since kickoff + this offset. */
    videoOffsetMs: Long = 10_000
)

/** Seeded match generator: a possession loop driven by a scenario (styles, phases, beats).
  *
  * Pure from the outside: the same config always yields the same envelopes. Internally one
  * simulation instance holds mutable state and the match's only RNG.
  */
object MatchGenerator:
  def generate(config: GeneratorConfig): Vector[Envelope] = Simulation(config).run()

  def toNdjson(envelopes: Vector[Envelope]): String =
    envelopes.iterator.map(_.asJson.noSpaces).mkString("", "\n", "\n")

/** One match in progress. Behaviour lives in the mixed-in traits; this class holds the state. */
private[generator] final class Simulation(val cfg: GeneratorConfig)
    extends Emitter,
      Actions,
      Restarts,
      Discipline:
  val rng = Rng(cfg.seed)
  val scenario: Scenario = cfg.scenario
  val teams: Vector[TeamState] = Vector(TeamState(Squads.home), TeamState(Squads.away))
  val matchId: MatchId = MatchId.unsafe(s"SYN-${cfg.seed}")
  val out = Vector.newBuilder[Envelope]
  val goals: Array[Int] = Array(0, 0)

  var seq = 0L
  var period = 1
  var clockMs = 0L // period clock
  var firstHalfEndMs = 0L

  // Ball and possession; `ball` is in the possessing team's frame.
  var team = 0
  var holder = 0 // formation slot of the ball carrier
  var ball: Pt = Grid.Centre
  var restart: Option[Restart] = None
  var possession = 0
  var sequenceIndex = 0
  var lastPass: Option[(EventId, Int)] = None // completed pass and its receiver slot
  var underPressure = false
  var possStartMs = 0L
  var possStartX = 0.0
  var possWonInPlay = false // possession began with a tackle or interception
  var passesInPossession = 0
  var counterBoost = 1.0 // counter-goal beat boost for this possession

  private val beatsDone = mutable.Set.empty[Int]

  def run(): Vector[Envelope] =
    emitMeta(Op.Create, MatchStatus.Scheduled, at = -60_000)
    teams.zipWithIndex.foreach((t, i) =>
      emit(PayloadType.Lineup, Payload.Team(t.squad.lineup), -59_990 + i * 10)
    )
    emitMeta(Op.Update, MatchStatus.Live, at = 0)
    playPeriod(1, kickingOff = 0, stoppageS = rng.between(60, 240))
    playPeriod(2, kickingOff = 1, stoppageS = rng.between(120, 360))
    emitMeta(Op.Update, MatchStatus.FullTime, at = wallMs + 1_000)
    out.result()

  private def playPeriod(p: Int, kickingOff: Int, stoppageS: Double): Unit =
    period = p
    clockMs = 0
    if p == 2 then planSubstitutions()
    val endMs = HalfMs + StrictMath.round(stoppageS) * 1000
    emitEvent(EventType.PeriodStart, kickingOff)
    startPossession(kickingOff, Grid.Centre, taker(kickingOff), Some(Restart(SetPieceKind.KickOff)))
    while clockMs < endMs do step()
    emitEvent(EventType.PeriodEnd, team)
    if p == 1 then firstHalfEndMs = clockMs

  private def step(): Unit =
    if counterBoost > 1 && !isCounter then
      counterBoost = 1 // the boost only lasts while it is a counter
    restart match
      case Some(r) =>
        maybeSubstitute()
        takeRestart(r)
      case None =>
        if contest() then
          if rng.chance(shotChance) then shoot(ShotSituation.OpenPlay)
          else pass(PassType.OpenPlay)

  // ---- scenario ----

  def minute: Double = Scenario.displayMinute(period, clockMs / 1000.0)
  def side(t: Int): Side = Side.values(t)
  def knobs(t: Int): TeamStyle = scenario.knobs(side(t), minute)

  private def strength(kind: BeatKind): Double = kind match
    case BeatKind.CounterGoal => 16.0
    case BeatKind.RedCard     => 12.0

  /** Multiplier (>= 1) from any open beat of this kind for team `t` near the current minute. */
  def boost(kind: BeatKind, t: Int): Double =
    scenario.beatList.zipWithIndex
      .collect {
        case (b, i) if b.kind == kind && b.team == side(t) && b.period == period && !beatsDone(i) =>
          1 + strength(kind) * Scenario.ramp(clockMs / 60_000.0 - b.minuteInPeriod)
      }
      .maxOption
      .getOrElse(1.0)

  /** Closes beats of this kind for team `t` that are currently in their window. */
  def beatHappened(kind: BeatKind, t: Int): Unit =
    scenario.beatList.zipWithIndex.foreach { (b, i) =>
      val inWindow = Scenario.ramp(clockMs / 60_000.0 - b.minuteInPeriod) > 0
      if b.kind == kind && b.team == side(t) && b.period == period && inWindow then beatsDone += i
    }

  // ---- team shape ----

  /** Where a player stands, shifted with the ball (both in that team's frame). */
  def position(slot: Int, ballAt: Pt): Pt =
    val a = Squads.Anchors(slot)
    if slot == 0 then Pt(a.x + math.max(0, (ballAt.x - 52.5) * 0.12), a.y)
    else
      // The front three make runs into the box once the ball reaches the attacking half.
      val run = if slot >= 8 then math.max(0, ballAt.x - 55) * 0.45 else 0.0
      Pt(
        Grid.clamp(a.x + (ballAt.x - 52.5) * 0.45 + run, 3, 100),
        Grid.clamp(a.y + (ballAt.y - 34) * 0.2 + (if run > 0 then (34 - a.y) * 0.3 else 0), 2, 66)
      )

  /** The on-pitch outfield player of team `t` closest to `spot` (in that team's frame). */
  def nearest(t: Int, spot: Pt): Int =
    teams(t).outfield.minBy(i => position(i, spot).dist(spot))

  /** Kick-off and penalty taker: the striker if still on, else the most advanced outfield slot. */
  def taker(t: Int): Int =
    if teams(t).isOnPitch(Squads.StrikerIndex) then Squads.StrikerIndex else teams(t).outfield.max

  /** Possession that began with a ball win in the team's own half and is still fast and short. */
  def isCounter: Boolean =
    possWonInPlay && possStartX < 52.5 && clockMs - possStartMs <= 15_000 && passesInPossession <= 5
