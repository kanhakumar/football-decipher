package smi.generator

import io.circe.syntax.*
import java.time.Instant
import scala.collection.immutable.ListMap
import smi.model.*

final case class GeneratorConfig(
    seed: Long,
    kickoff: Instant = Instant.parse("2026-10-18T15:00:00Z"),
    /** Broadcast pre-roll: video_ts_ms = wall time since kickoff + this offset. */
    videoOffsetMs: Long = 10_000
)

/** Seeded match generator v0: a possession loop that emits only passes and shots.
  *
  * Pure from the outside: the same config always yields the same envelopes. Internally one
  * simulation instance holds mutable state and the match's only RNG.
  */
object MatchGenerator:
  def generate(config: GeneratorConfig): Vector[Envelope] = Simulation(config).run()

  def toNdjson(envelopes: Vector[Envelope]): String =
    envelopes.iterator.map(_.asJson.noSpaces).mkString("", "\n", "\n")

private final class Simulation(cfg: GeneratorConfig):
  private val rng = Rng(cfg.seed)
  private val squads = Vector(Squads.home, Squads.away)
  private val matchId = MatchId.unsafe(s"SYN-${cfg.seed}")
  private val out = Vector.newBuilder[Envelope]

  private val HalfMs = 45L * 60 * 1000
  private val HalfTimeBreakMs = 15L * 60 * 1000

  private var seq = 0L
  private var period = 1
  private var clockMs = 0L // period clock
  private var firstHalfEndMs = 0L

  private var team = 0 // index into squads of the team in possession
  private var holder = 0 // index into that team's starters
  private var ball = Grid.Centre // in the possessing team's frame
  private var restart: Option[PassType] = None
  private var possession = 0
  private var sequenceIndex = 0
  private var lastPass: Option[(EventId, Int)] = None // completed pass id and its receiver
  private val goals = Array(0, 0)

  def run(): Vector[Envelope] =
    emitMeta(Op.Create, MatchStatus.Scheduled, at = -60_000)
    squads.zipWithIndex.foreach((s, i) =>
      emit(PayloadType.Lineup, Payload.Team(s.lineup), -59_990 + i * 10)
    )
    emitMeta(Op.Update, MatchStatus.Live, at = 0)
    playPeriod(1, kickingOff = 0, stoppageS = rng.between(60, 240))
    playPeriod(2, kickingOff = 1, stoppageS = rng.between(120, 360))
    emitMeta(Op.Update, MatchStatus.FullTime, at = wallMs + 1_000)
    out.result()

  // ---- clocks and emission ----

  private def wallMs: Long =
    if period == 1 then clockMs else firstHalfEndMs + HalfTimeBreakMs + clockMs

  private def advance(seconds: Double): Unit =
    clockMs += math.max(100L, StrictMath.round(seconds * 10) * 100)

  private def emit(pt: PayloadType, payload: Payload, wallMs: Long, op: Op = Op.Create): Unit =
    out += Envelope(
      Envelope.SchemaVersion,
      seq,
      matchId,
      Source.Synthetic,
      op,
      Timestamp.of(cfg.kickoff.plusMillis(wallMs + 100)),
      pt,
      payload
    )
    seq += 1

  private def emitMeta(op: Op, status: MatchStatus, at: Long): Unit =
    val meta = MatchMeta(
      matchId = matchId,
      competition = "Synthetic League",
      season = "2026/27",
      kickoffUtc = Timestamp.of(cfg.kickoff),
      status = status,
      homeTeamId = squads(0).teamId,
      awayTeamId = squads(1).teamId,
      pitch = PitchSize(105, 68),
      score = ListMap(squads(0).teamId.value -> goals(0), squads(1).teamId.value -> goals(1)),
      seed = Some(cfg.seed),
      dataOrigin = DataOrigin.Synthetic
    )
    emit(PayloadType.MatchMeta, Payload.Meta(meta), at, op)

  private def emitEvent(
      t: EventType,
      at: Pt,
      outcome: Outcome,
      details: EventDetails
  ): EventId =
    val id = EventId.unsafe(f"E_$seq%06d")
    val periodClockS = clockMs / 1000.0
    val event = Event(
      eventId = id,
      `type` = t,
      period = period,
      periodClockS = periodClockS,
      // Total elapsed playing time, so it stays monotonic across halves (ADR-005).
      matchClockS = (if period == 1 then 0L else firstHalfEndMs) / 1000.0 + periodClockS,
      wallclockUtc = Timestamp.of(cfg.kickoff.plusMillis(wallMs)),
      videoTsMs = wallMs + cfg.videoOffsetMs,
      teamId = squads(team).teamId,
      playerId = Some(squads(team).starters(holder).playerId),
      x = Some(at.x),
      y = Some(at.y),
      possessionId = Some(PossessionId.unsafe(f"POS_$possession%04d")),
      sequenceIndex = Some(sequenceIndex),
      outcome = Some(outcome),
      details = Some(details)
    )
    emit(PayloadType.Event, Payload.Ev(event), wallMs)
    sequenceIndex += 1
    id

  // ---- match flow ----

  private def playPeriod(p: Int, kickingOff: Int, stoppageS: Double): Unit =
    period = p
    clockMs = 0
    val endMs = HalfMs + StrictMath.round(stoppageS) * 1000
    newPossession(kickingOff, Grid.Centre, Squads.StrikerIndex, Some(PassType.KickOff))
    while clockMs < endMs do
      if restart.isEmpty && rng.chance(Grid.shotChance(ball)) then shoot() else pass()
    if p == 1 then firstHalfEndMs = clockMs

  private def newPossession(t: Int, at: Pt, player: Int, how: Option[PassType]): Unit =
    team = t
    ball = at.clamped.rounded
    holder = player
    restart = how
    possession += 1
    sequenceIndex = 0
    lastPass = None

  /** Hands the ball to the other team at a spot given in the old team's frame. */
  private def turnover(
      at: Pt,
      how: Option[PassType],
      deadS: Double,
      player: Option[Int] = None
  ): Unit =
    val spot = at.mirror
    val other = 1 - team
    newPossession(other, spot, player.getOrElse(nearest(spot)), how)
    advance(deadS)

  // ---- team shape ----

  /** Where a player stands, shifted with the ball (both in that team's frame). */
  private def position(player: Int, ballAt: Pt): Pt =
    val a = Squads.Anchors(player)
    if player == 0 then Pt(a.x + math.max(0, (ballAt.x - 52.5) * 0.12), a.y)
    else
      // The front three make runs into the box once the ball reaches the attacking half.
      val run = if player >= 8 then math.max(0, ballAt.x - 55) * 0.45 else 0.0
      Pt(
        Grid.clamp(a.x + (ballAt.x - 52.5) * 0.45 + run, 3, 100),
        Grid.clamp(a.y + (ballAt.y - 34) * 0.2 + (if run > 0 then (34 - a.y) * 0.3 else 0), 2, 66)
      )

  private def nearest(spot: Pt): Int =
    (1 until 11).minBy(i => position(i, spot).dist(spot))

  // ---- actions ----

  private def pass(): Unit =
    val passType = restart.getOrElse(PassType.OpenPlay)
    restart = None
    val from = ball
    val receiver = chooseReceiver(from)
    val spread = 2.0 + from.dist(position(receiver, from)) * 0.08
    val aim = position(receiver, from)
    val target =
      Pt(aim.x + rng.gaussian() * spread, aim.y + rng.gaussian() * spread).clamped.rounded
    val length = from.dist(target)
    val height =
      if passType == PassType.Corner || length > 32 then
        rng.weighted(Seq(PassHeight.High -> 0.7, PassHeight.Low -> 0.2, PassHeight.Ground -> 0.1))
      else
        rng.weighted(Seq(PassHeight.Ground -> 0.85, PassHeight.Low -> 0.1, PassHeight.High -> 0.05))
    val outcome =
      if rng.chance(successChance(from, target, passType)) then Outcome.Complete
      else failure(from, target, passType)
    val details = PassDetails(
      endX = target.x,
      endY = target.y,
      lengthM = Grid.round1(length),
      angleRad = Some(
        StrictMath.round(StrictMath.atan2(target.y - from.y, target.x - from.x) * 100) / 100.0
      ),
      height = Some(height),
      bodyPart = Some(if passType == PassType.ThrowIn then BodyPart.Other else footOrHead(height)),
      recipientId =
        Option.when(outcome == Outcome.Complete)(squads(team).starters(receiver).playerId),
      passType = Some(passType),
      isThroughBall = Some(height == PassHeight.Ground && target.x - from.x > 15 && target.x > 70),
      isCross = Some(Grid.inBox(target) && (from.y < 15 || from.y > 53) && from.x > 75),
      isSwitch = Some(math.abs(target.y - from.y) > 35)
    )
    val id = emitEvent(EventType.Pass, from, outcome, details)
    val speed = height match
      case PassHeight.Ground => 14.0
      case PassHeight.Low    => 16.0
      case PassHeight.High   => 18.0
    advance(length / speed + 0.2)
    outcome match
      case Outcome.Complete =>
        holder = receiver
        ball = target
        lastPass = Some((id, receiver))
        advance(rng.between(0.6, 3.2))
      case Outcome.Out =>
        val touchline = Pt(target.x, if target.y < 34 then 0.5 else 67.5)
        turnover(touchline, Some(PassType.ThrowIn), rng.between(12, 30))
      case Outcome.Offside     => turnover(target, Some(PassType.FreeKick), rng.between(15, 30))
      case Outcome.Intercepted => turnover(target, None, rng.between(0.3, 1.0))
      case _ =>
        val loose = Pt(target.x + rng.gaussian() * 3, target.y + rng.gaussian() * 3)
        turnover(loose, None, rng.between(1.0, 3.0))

  private def chooseReceiver(from: Pt): Int =
    val options = (0 until 11).filter(_ != holder).map { j =>
      val p = position(j, from)
      val d = p.dist(from)
      val forward = 1 + 0.5 * Grid.clamp((p.x - from.x) / 15, -0.8, 1.2)
      val keeper = if j == 0 then 0.25 else 1.0
      j -> (if d < 3 then 0.0 else StrictMath.exp(-d / 15) * forward * keeper)
    }
    rng.weighted(options)

  private def successChance(from: Pt, to: Pt, passType: PassType): Double =
    if passType == PassType.Corner then 0.45
    else
      val length = from.dist(to)
      var p = 0.95 - 0.0045 * math.max(0, length - 12)
      if Grid.inFinalThird(to) then p -= 0.07
      if Grid.inBox(to) then p -= 0.12
      if to.x - from.x > 20 then p -= 0.05
      if passType == PassType.ThrowIn then p += 0.03
      Grid.clamp(p, 0.35, 0.97)

  private def failure(from: Pt, to: Pt, passType: PassType): Outcome =
    val offsideRisk = passType == PassType.OpenPlay && to.x > 70 && to.x - from.x > 8
    if offsideRisk && rng.chance(0.06) then Outcome.Offside
    else
      rng.weighted(
        Seq(Outcome.Incomplete -> 0.45, Outcome.Intercepted -> 0.42, Outcome.Out -> 0.13)
      )

  private def footOrHead(height: PassHeight): BodyPart =
    if height == PassHeight.High && rng.chance(0.1) then BodyPart.Head
    else rng.weighted(Seq(BodyPart.RightFoot -> 0.72, BodyPart.LeftFoot -> 0.28))

  private def shoot(): Unit =
    val from = ball
    val distance = from.dist(Grid.Goal)
    val header = distance < 14 && rng.chance(0.15)
    val bodyPart = if header then BodyPart.Head else footOrHead(PassHeight.Ground)
    val goalChance = (0.6 * StrictMath.exp(-distance / 7.5) + 0.03) * (if header then 0.6 else 1.0)
    val outcome =
      if rng.chance(goalChance) then Outcome.Goal
      else
        rng.weighted(
          Seq(
            Outcome.Saved -> 0.32,
            Outcome.Blocked -> 0.28,
            Outcome.OffTarget -> 0.36,
            Outcome.Post -> 0.04
          )
        )
    val assist = lastPass.collect { case (id, receiver) if receiver == holder => id }
    emitEvent(
      EventType.Shot,
      from,
      outcome,
      ShotDetails(Some(bodyPart), ShotSituation.OpenPlay, assistEventId = assist)
    )
    advance(1.0)
    outcome match
      case Outcome.Goal =>
        goals(team) += 1
        turnover(
          Grid.Centre,
          Some(PassType.KickOff),
          rng.between(45, 80),
          Some(Squads.StrikerIndex)
        )
      case Outcome.Saved if rng.chance(0.5) =>
        turnover(Pt(99, 34), None, rng.between(3, 8), Some(0))
      case Outcome.Saved =>
        val corner = Pt(104.5, if rng.chance(0.5) then 0.5 else 67.5)
        val taker = if corner.y < 34 then 8 else 10
        ball = corner
        holder = taker
        restart = Some(PassType.Corner)
        lastPass = None
        advance(rng.between(20, 40))
      case Outcome.Blocked if rng.chance(0.5) =>
        ball = Pt(from.x - 6, from.y).clamped.rounded
        holder = nearest(ball)
        lastPass = None
        advance(rng.between(0.5, 2.0))
      case Outcome.Blocked => turnover(from, None, rng.between(0.5, 2.0))
      case _ =>
        val goalKick = Pt(99.5, if rng.chance(0.5) then 24.84 else 43.16)
        turnover(goalKick, Some(PassType.GoalKick), rng.between(20, 35), Some(0))
