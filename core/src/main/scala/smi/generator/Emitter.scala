package smi.generator

import scala.collection.immutable.ListMap
import smi.model.*

/** Clocks, sequence numbers and envelope construction for a running simulation. */
private[generator] trait Emitter:
  this: Simulation =>

  val HalfMs: Long = 45L * 60 * 1000
  val HalfTimeBreakMs: Long = 15L * 60 * 1000

  /** Milliseconds since kickoff on the wall clock (half-time break included). */
  def wallMs: Long =
    if period == 1 then clockMs else firstHalfEndMs + HalfTimeBreakMs + clockMs

  /** Moves the period clock forward, in whole tenths of a second (at least one). */
  def advance(seconds: Double): Unit =
    clockMs += math.max(100L, StrictMath.round(seconds * 10) * 100)

  def teamId(t: Int): TeamId = teams(t).squad.teamId
  def pid(t: Int, slot: Int): Option[PlayerId] = Some(teams(t).player(slot).playerId)

  def emit(pt: PayloadType, payload: Payload, at: Long, op: Op = Op.Create): Unit =
    out += Envelope(
      Envelope.SchemaVersion,
      seq,
      matchId,
      Source.Synthetic,
      op,
      Timestamp.of(cfg.kickoff.plusMillis(at + 100)),
      pt,
      payload
    )
    seq += 1

  def emitMeta(op: Op, status: MatchStatus, at: Long): Unit =
    val meta = MatchMeta(
      matchId = matchId,
      competition = "Synthetic League",
      season = "2026/27",
      kickoffUtc = Timestamp.of(cfg.kickoff),
      status = status,
      homeTeamId = teamId(0),
      awayTeamId = teamId(1),
      pitch = PitchSize(105, 68),
      score = ListMap(teamId(0).value -> goals(0), teamId(1).value -> goals(1)),
      seed = Some(cfg.seed),
      dataOrigin = DataOrigin.Synthetic
    )
    emit(PayloadType.MatchMeta, Payload.Meta(meta), at, op)

  /** Emits an event by team `by` at the current clock. `at` is in that team's frame. */
  def emitEvent(
      t: EventType,
      by: Int,
      player: Option[PlayerId] = None,
      at: Option[Pt] = None,
      outcome: Option[Outcome] = None,
      details: Option[EventDetails] = None,
      pressured: Boolean = false
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
      teamId = teamId(by),
      playerId = player,
      x = at.map(_.x),
      y = at.map(_.y),
      possessionId = Some(PossessionId.unsafe(f"POS_$possession%04d")),
      sequenceIndex = Some(sequenceIndex),
      underPressure = Option.when(pressured)(true),
      outcome = outcome,
      details = details
    )
    emit(PayloadType.Event, Payload.Ev(event), wallMs)
    sequenceIndex += 1
    id
