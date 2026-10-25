package smi.model

import io.circe.Codec
import smi.model.Check.*
import scala.collection.immutable.ListMap

final case class PitchSize(lengthM: Double, widthM: Double)
object PitchSize:
  given Codec.AsObject[PitchSize] = Wire.checked(Wire.codec[PitchSize]) { p =>
    range("pitch.length_m", p.lengthM, 90, 120) ++ range("pitch.width_m", p.widthM, 45, 90)
  }

final case class MatchMeta(
    matchId: MatchId,
    competition: String,
    season: String,
    kickoffUtc: Timestamp,
    status: MatchStatus,
    homeTeamId: TeamId,
    awayTeamId: TeamId,
    /** Period ("1".."5") to team id to real pitch direction. Events stay team-relative. */
    attackingDirection: Option[ListMap[String, ListMap[String, Direction]]] = None,
    pitch: PitchSize,
    score: ListMap[String, Int],
    seed: Option[Long] = None,
    dataOrigin: DataOrigin
)
object MatchMeta:
  import Wire.given

  given Codec.AsObject[MatchMeta] = Wire.checked(Wire.codec[MatchMeta]) { m =>
    opt(m.attackingDirection)(_.keys.toList.flatMap { k =>
      that(k.matches("^[1-5]$"), s"attacking_direction: period key '$k' must be 1..5")
    }) ++ m.score.toList.flatMap((team, goals) => atLeast(s"score.$team", goals, 0))
  }

/** A squad member. Generator attributes never appear here (ADR-007). */
final case class Player(
    playerId: PlayerId,
    name: String,
    shirt: Int,
    position: Position,
    slot: Option[String] = None
)
object Player:
  given Codec.AsObject[Player] = Wire.checked(Wire.codec[Player]) { p =>
    that(p.name.nonEmpty, "player.name must not be empty") ++ range("shirt", p.shirt, 1, 99)
  }

final case class Lineup(
    teamId: TeamId,
    name: String,
    short: String,
    colors: Option[List[HexColor]] = None,
    formation: Formation,
    starters: List[Player],
    bench: List[Player]
)
object Lineup:
  given Codec.AsObject[Lineup] = Wire.checked(Wire.codec[Lineup]) { l =>
    range("short.length", l.short.length, 2, 4) ++
      opt(l.colors)(c => size("colors", c.size, 2, 2)) ++
      size("starters", l.starters.size, 11, 11) ++ size("bench", l.bench.size, 0, 15)
  }

final case class Ball(
    x: Double,
    y: Double,
    z: Option[Double] = None,
    speedMs: Option[Double] = None,
    state: Option[BallState] = None
)
object Ball:
  given Codec.AsObject[Ball] = Wire.checked(Wire.codec[Ball]) { b =>
    Tracking.xyErrors(b.x, b.y) ++ opt(b.z)(range("ball.z", _, 0, 30)) ++
      opt(b.speedMs)(range("ball.speed_ms", _, 0, 60))
  }

final case class TrackedPlayer(
    playerId: PlayerId,
    teamId: TeamId,
    x: Double,
    y: Double,
    speedMs: Option[Double] = None,
    role: Option[TrackedRole] = None
)
object TrackedPlayer:
  given Codec.AsObject[TrackedPlayer] = Wire.checked(Wire.codec[TrackedPlayer]) { p =>
    Tracking.xyErrors(p.x, p.y) ++ opt(p.speedMs)(range("player.speed_ms", _, 0, 13))
  }

final case class TrackedOfficial(
    id: OfficialId,
    role: OfficialRole,
    x: Double,
    y: Double,
    speedMs: Option[Double] = None
)
object TrackedOfficial:
  given Codec.AsObject[TrackedOfficial] = Wire.checked(Wire.codec[TrackedOfficial]) { o =>
    Tracking.xyErrors(o.x, o.y) ++ opt(o.speedMs)(range("official.speed_ms", _, 0, 10))
  }

final case class TrackingFrame(
    frame: Long,
    period: Int,
    periodClockS: Double,
    wallclockUtc: Timestamp,
    videoTsMs: Long,
    hz: Double,
    ball: Ball,
    players: List[TrackedPlayer],
    officials: Option[List[TrackedOfficial]] = None
)
object TrackingFrame:
  given Codec.AsObject[TrackingFrame] = Wire.checked(Wire.codec[TrackingFrame]) { f =>
    atLeast("frame", f.frame.toDouble, 0) ++ range("period", f.period, 1, 5) ++
      atLeast("period_clock_s", f.periodClockS, 0) ++
      atLeast("video_ts_ms", f.videoTsMs.toDouble, 0) ++
      above("hz", f.hz, 0) ++ range("hz", f.hz, 0, 50) ++
      size("players", f.players.size, 0, 22) ++
      opt(f.officials)(o => size("officials", o.size, 0, 5))
  }

/** Tracking coordinates may sit a few metres outside the pitch (players off the touchline). */
object Tracking:
  def xyErrors(x: Double, y: Double): List[String] =
    range("x", x, -5, 110) ++ range("y", y, -5, 73)
