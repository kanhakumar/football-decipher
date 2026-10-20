package smi

import java.time.Instant
import scala.collection.immutable.ListMap
import smi.model.*

/** Builds small, hand-made canonical feeds for engine tests. */
object EventKit:
  val home: TeamId = TeamId.unsafe("T_HOM")
  val away: TeamId = TeamId.unsafe("T_AWY")
  val matchId: MatchId = MatchId.unsafe("TEST-1")
  private val kickoff = Instant.parse("2026-10-18T15:00:00Z")

  def player(team: TeamId, shirt: Int): PlayerId =
    PlayerId.unsafe(f"P_${team.value.drop(2)}_$shirt%02d")

  val meta: MatchMeta = MatchMeta(
    matchId = matchId,
    competition = "Test League",
    season = "2026/27",
    kickoffUtc = Timestamp.of(kickoff),
    status = MatchStatus.Live,
    homeTeamId = home,
    awayTeamId = away,
    pitch = PitchSize(105, 68),
    score = ListMap(home.value -> 0, away.value -> 0),
    dataOrigin = DataOrigin.Synthetic
  )

  def lineup(team: TeamId): Lineup =
    val starters = (1 to 11).toList.map(n =>
      Player(player(team, n), s"Player $n", n, if n == 1 then Position.GK else Position.CM)
    )
    val bench = (12 to 14).toList.map(n => Player(player(team, n), s"Player $n", n, Position.CM))
    Lineup(team, team.value, "TST", None, Formation.unsafe("4-3-3"), starters, bench)

  private var seq = 0L
  private def next(): Long = { seq += 1; seq }

  def envelope(payload: Payload, pt: PayloadType, op: Op = Op.Create): Envelope =
    Envelope(
      Envelope.SchemaVersion,
      next(),
      matchId,
      Source.Synthetic,
      op,
      Timestamp.of(kickoff),
      pt,
      payload
    )

  val header: Vector[Envelope] = Vector(
    envelope(Payload.Meta(meta), PayloadType.MatchMeta),
    envelope(Payload.Team(lineup(home)), PayloadType.Lineup),
    envelope(Payload.Team(lineup(away)), PayloadType.Lineup)
  )

  def event(
      id: Long,
      t: EventType,
      team: TeamId,
      shirt: Option[Int],
      at: Option[(Double, Double)],
      clockS: Double,
      period: Int = 1,
      outcome: Option[Outcome] = None,
      details: Option[EventDetails] = None,
      pressured: Boolean = false
  ): Event =
    val wall = kickoff.plusMillis(StrictMath.round(clockS * 1000))
    Event(
      eventId = EventId.unsafe(f"E_$id%06d"),
      `type` = t,
      period = period,
      periodClockS = clockS,
      matchClockS = (if period == 1 then 0 else 2700) + clockS,
      wallclockUtc = Timestamp.of(wall),
      videoTsMs = StrictMath.round(clockS * 1000) + 10_000,
      teamId = team,
      playerId = shirt.map(player(team, _)),
      x = at.map(_._1),
      y = at.map(_._2),
      underPressure = Option.when(pressured)(true),
      outcome = outcome,
      details = details
    )

  def pass(
      id: Long,
      team: TeamId,
      shirt: Int,
      from: (Double, Double),
      to: (Double, Double),
      clockS: Double,
      complete: Boolean,
      pressured: Boolean = false
  ): Event =
    val length = StrictMath.hypot(to._1 - from._1, to._2 - from._2)
    event(
      id,
      EventType.Pass,
      team,
      Some(shirt),
      Some(from),
      clockS,
      outcome = Some(if complete then Outcome.Complete else Outcome.Incomplete),
      details = Some(PassDetails(to._1, to._2, length)),
      pressured = pressured
    )

  def shot(
      id: Long,
      team: TeamId,
      shirt: Int,
      at: (Double, Double),
      clockS: Double,
      outcome: Outcome,
      situation: ShotSituation = ShotSituation.OpenPlay
  ): Event =
    event(
      id,
      EventType.Shot,
      team,
      Some(shirt),
      Some(at),
      clockS,
      outcome = Some(outcome),
      details = Some(ShotDetails(Some(BodyPart.RightFoot), situation))
    )

  def foul(
      id: Long,
      team: TeamId,
      shirt: Int,
      at: (Double, Double),
      clockS: Double,
      card: Card,
      victim: PlayerId
  ): Event =
    event(
      id,
      EventType.Foul,
      team,
      Some(shirt),
      Some(at),
      clockS,
      details = Some(FoulDetails(victim, card))
    )

  def create(e: Event): Envelope = envelope(Payload.Ev(e), PayloadType.Event)
  def update(e: Event): Envelope = envelope(Payload.Ev(e), PayloadType.Event, Op.Update)
  def delete(id: EventId): Envelope =
    envelope(Payload.Delete(DeleteRef(id)), PayloadType.Event, Op.Delete)
