package smi.generator

import io.circe.{Codec, Decoder}
import io.circe.yaml.v12.parser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.io.Source
import smi.model.{Wire, WireEnum}

enum Side:
  case Home, Away
  def index: Int = ordinal
object Side extends WireEnum[Side]("team"):
  def all = values

enum BeatKind:
  case CounterGoal, RedCard
object BeatKind extends WireEnum[BeatKind]("beat.kind"):
  def all = values

/** Style knobs, each roughly 0..1 (tempo is a multiplier around 1). */
final case class TeamStyle(press: Double, directness: Double, tempo: Double, aggression: Double)
object TeamStyle:
  given Codec.AsObject[TeamStyle] = Wire.codec

final case class Teams(home: TeamStyle, away: TeamStyle)
object Teams:
  given Codec.AsObject[Teams] = Wire.codec

/** Overrides one team's knobs between two display minutes (for example a high-press spell). */
final case class Phase(
    team: Side,
    fromMinute: Double,
    toMinute: Double,
    press: Option[Double] = None,
    directness: Option[Double] = None,
    tempo: Option[Double] = None,
    aggression: Option[Double] = None
)
object Phase:
  given Codec.AsObject[Phase] = Wire.codec

/** A soft story beat: nudges probabilities around a display minute, never forces the event. */
final case class Beat(kind: BeatKind, team: Side, minute: Double):
  def period: Int = if minute <= 45 then 1 else 2
  def minuteInPeriod: Double = if period == 1 then minute else minute - 45
object Beat:
  given Codec.AsObject[Beat] = Wire.codec

/** Generator-only match script. The pipeline never reads it (CLAUDE.md, ADR-007). */
final case class Scenario(
    name: String,
    teams: Teams,
    phases: Option[List[Phase]] = None,
    beats: Option[List[Beat]] = None
):
  def style(side: Side): TeamStyle = if side == Side.Home then teams.home else teams.away

  /** Knobs for a team at a display minute, with any active phase applied. */
  def knobs(side: Side, minute: Double): TeamStyle =
    phases
      .getOrElse(Nil)
      .filter(p => p.team == side && minute >= p.fromMinute && minute < p.toMinute)
      .foldLeft(style(side)) { (s, p) =>
        TeamStyle(
          p.press.getOrElse(s.press),
          p.directness.getOrElse(s.directness),
          p.tempo.getOrElse(s.tempo),
          p.aggression.getOrElse(s.aggression)
        )
      }

  def beatList: List[Beat] = beats.getOrElse(Nil)

object Scenario:
  given Codec.AsObject[Scenario] = Wire.codec

  /** Display minute as on a broadcast clock: the second half starts at 45. */
  def displayMinute(period: Int, periodClockS: Double): Double =
    (if period == 1 then 0 else 45) + periodClockS / 60

  /** How strongly a beat nudges at `dm` minutes from its target: ramps up over the 5 minutes
    * before, holds for 4 minutes after, then fades out over 2.
    */
  def ramp(dm: Double): Double =
    if dm < -5 then 0
    else if dm <= 0 then (dm + 5) / 5
    else if dm <= 4 then 1
    else math.max(0, 1 - (dm - 4) / 2)

  def parse(yaml: String): Either[String, Scenario] =
    parser.parse(yaml).flatMap(Decoder[Scenario].decodeJson).left.map(_.getMessage)

  def load(path: Path): Either[String, Scenario] = parse(Files.readString(path, UTF_8))

  lazy val default: Scenario =
    val stream = getClass.getResourceAsStream("/scenarios/default.yaml")
    val text = Source.fromInputStream(stream, UTF_8.name).mkString
    parse(text).fold(e => throw IllegalStateException(s"default scenario: $e"), identity)
