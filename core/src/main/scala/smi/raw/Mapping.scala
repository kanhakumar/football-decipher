package smi.raw

import io.circe.Json
import smi.Pitch
import smi.model.*

/** Conversions shared by the raw emitter and the adapter. Raw values are exact inverses of what the
  * adapter produces, so raw -> canonical -> raw is lossless (ADR-009).
  */
object Mapping:
  // ---- coordinates: canonical metres <-> raw 0..100 ----

  private def round(v: Double, places: Int): Double =
    val f = StrictMath.pow(10, places)
    StrictMath.round(v * f) / f

  def toRawX(m: Double): Double = round(m * 100 / Pitch.Length, 2)
  def toRawY(m: Double): Double = round(m * 100 / Pitch.Width, 2)
  def fromRawX(r: Double): Double = round(r * Pitch.Length / 100, 4)
  def fromRawY(r: Double): Double = round(r * Pitch.Width / 100, 4)

  /** Tolerance for raw coordinates just outside 0..100, which are clamped with an issue. */
  val CoordinateSlack = 1.0

  // ---- numbers as qualifier strings ----

  def fmt(d: Double): String = BigDecimal(d).bigDecimal.stripTrailingZeros.toPlainString

  // ---- clocks: the raw clock is match minute + second ----

  /** First minute of each period on the broadcast clock. */
  def baseMinute(period: Int): Int = period match
    case 1 => 0
    case 2 => 45
    case 3 => 90
    case 4 => 105
    case _ => 120

  def toRawClock(period: Int, periodClockS: Double): (Int, Int) =
    val whole = StrictMath.floor(periodClockS).toLong
    (baseMinute(period) + (whole / 60).toInt, (whole % 60).toInt)

  def fromRawClock(period: Int, min: Int, sec: Int): Either[String, Double] =
    val offset = min - baseMinute(period)
    if offset < 0 then Left(s"minute $min is before period $period starts")
    else Right(offset * 60.0 + sec)

  /** Nominal period length, used for match_clock_s until a period_end is seen. */
  def nominalLengthS(period: Int): Double =
    if period <= 2 then 2700 else if period <= 4 then 900 else 0

  // ---- IDs ----

  def eventId(raw: Long): EventId = EventId.unsafe(f"E_$raw%06d")
  def rawEventId(id: EventId): Long = id.value.drop(2).toLong

  /** Canonical player ID for a shirt in a team: P_<team suffix>_<shirt>. */
  def playerId(team: TeamId, shirt: Int): PlayerId =
    PlayerId.unsafe(f"P_${team.value.drop(2)}_$shirt%02d")

  /** Provider player ID the emitter assigns: team ID * 1000 + shirt. */
  def rawPlayerId(rawTeam: Int, shirt: Int): Long = rawTeam.toLong * 1000 + shirt

  // ---- outcomes: raw 0/1 plus a `result` qualifier when the bit is not enough ----

  private val positive = Set(Outcome.Complete, Outcome.Goal, Outcome.Won)

  def outcomeBit(o: Outcome): Int = if positive(o) then 1 else 0

  /** The outcome a type and bit imply on their own, if any. */
  def impliedOutcome(t: EventType, bit: Int): Option[Outcome] = (t, bit) match
    case (EventType.Pass, 1)                       => Some(Outcome.Complete)
    case (EventType.Shot, 1)                       => Some(Outcome.Goal)
    case (EventType.Dribble | EventType.Tackle, 1) => Some(Outcome.Won)
    case (EventType.Dribble | EventType.Tackle, 0) => Some(Outcome.Lost)
    case _                                         => None

  /** Used when the `result` qualifier is missing and the bit does not decide. */
  def defaultOutcome(t: EventType): Outcome = t match
    case EventType.Shot => Outcome.OffTarget
    case _              => Outcome.Incomplete

  // ---- qualifier values ----

  /** Event-level fields carried as qualifiers (everything else in qualifiers is `details`). */
  val EventLevel: Set[String] =
    Set("result", "possession_id", "sequence_index", "under_pressure", "related_event_ids")

  final case class Ids(
      teamToRaw: TeamId => Option[Int],
      teamFromRaw: Int => Option[TeamId],
      playerToRaw: PlayerId => Option[Long],
      playerFromRaw: Long => Option[PlayerId]
  )

  def qualifierValue(kind: QualifierKind, json: Json, ids: Ids): Either[String, String] =
    def num = json.asNumber.map(_.toDouble).toRight(s"expected a number, got ${json.noSpaces}")
    def str = json.asString.toRight(s"expected a string, got ${json.noSpaces}")
    kind match
      case QualifierKind.X      => num.map(m => fmt(toRawX(m)))
      case QualifierKind.Y      => num.map(m => fmt(toRawY(m)))
      case QualifierKind.Number => num.map(fmt)
      case QualifierKind.Int =>
        json.asNumber.flatMap(_.toLong).map(_.toString).toRight("expected an integer")
      case QualifierKind.String => str
      case QualifierKind.Flag =>
        json.asBoolean.map(b => if b then "1" else "0").toRight("expected a boolean")
      case QualifierKind.Player =>
        str
          .flatMap(PlayerId.from)
          .flatMap(p =>
            ids.playerToRaw(p).map(_.toString).toRight(s"no provider ID for ${p.value}")
          )
      case QualifierKind.Team =>
        str
          .flatMap(TeamId.from)
          .flatMap(t => ids.teamToRaw(t).map(_.toString).toRight(s"no provider ID for ${t.value}"))
      case QualifierKind.Event => str.flatMap(EventId.from).map(id => rawEventId(id).toString)
      case QualifierKind.Events =>
        json.asArray.toRight("expected an array").flatMap { items =>
          items.toList
            .map(_.asString.toRight("expected event IDs").flatMap(EventId.from).map(rawEventId))
            .partitionMap(identity) match
            case (Nil, ids)    => Right(ids.mkString(","))
            case (err :: _, _) => Left(err)
        }

  def canonicalValue(kind: QualifierKind, value: String, ids: Ids): Either[String, Json] =
    def num = value.toDoubleOption.toRight(s"'$value' is not a number")
    def long = value.toLongOption.toRight(s"'$value' is not an integer")
    kind match
      case QualifierKind.X      => num.map(r => Json.fromDoubleOrNull(fromRawX(r)))
      case QualifierKind.Y      => num.map(r => Json.fromDoubleOrNull(fromRawY(r)))
      case QualifierKind.Number => num.map(Json.fromDoubleOrNull)
      case QualifierKind.Int    => long.map(Json.fromLong)
      case QualifierKind.String => Right(Json.fromString(value))
      case QualifierKind.Flag =>
        value match
          case "1"   => Right(Json.True)
          case "0"   => Right(Json.False)
          case other => Left(s"flag '$other' is not 0 or 1")
      case QualifierKind.Player =>
        long
          .flatMap(r => ids.playerFromRaw(r).toRight(s"unknown provider player $r"))
          .map(p => Json.fromString(p.value))
      case QualifierKind.Team =>
        long
          .flatMap(r => ids.teamFromRaw(r.toInt).toRight(s"unknown provider team $r"))
          .map(t => Json.fromString(t.value))
      case QualifierKind.Event => long.map(r => Json.fromString(eventId(r).value))
      case QualifierKind.Events =>
        value
          .split(",")
          .toList
          .filter(_.nonEmpty)
          .map(_.toLongOption.toRight(s"bad event list '$value'"))
          .partitionMap(identity) match
          case (Nil, raws)   => Right(Json.arr(raws.map(r => Json.fromString(eventId(r).value))*))
          case (err :: _, _) => Left(err)
