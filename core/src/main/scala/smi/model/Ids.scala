package smi.model

import io.circe.{Codec, Decoder, Encoder}
import java.time.{Instant, OffsetDateTime}
import scala.util.Try

/** A string that must satisfy a check; the check runs on decode and in `from`. */
abstract class Refined[T](label: String, ok: String => Boolean, rule: String)(using
    toT: String =:= T
):
  def from(s: String): Either[String, T] =
    if ok(s) then Right(toT(s)) else Left(s"$label: '$s' must match $rule")

  def unsafe(s: String): T = from(s).fold(e => throw IllegalArgumentException(e), identity)

  extension (t: T) def value: String = toT.flip(t)

  given Codec[T] =
    Codec.from(Decoder.decodeString.emap(from), Encoder.encodeString.contramap(_.value))

abstract class Pattern[T](label: String, regex: String)(using String =:= T)
    extends Refined[T](label, _.matches(regex), regex)

opaque type MatchId = String
object MatchId extends Pattern[MatchId]("match_id", "^[A-Za-z0-9_-]{1,64}$")

opaque type TeamId = String
object TeamId extends Pattern[TeamId]("team_id", "^T_[A-Z0-9_]{1,16}$")

opaque type PlayerId = String
object PlayerId extends Pattern[PlayerId]("player_id", "^P_[A-Z0-9_]{1,24}$")

opaque type EventId = String
object EventId extends Pattern[EventId]("event_id", "^E_[0-9]{1,9}$")

opaque type PossessionId = String
object PossessionId extends Pattern[PossessionId]("possession_id", "^POS_[0-9]{1,6}$")

opaque type InsightId = String
object InsightId extends Pattern[InsightId]("insight_id", "^I_[0-9]{1,6}$")

opaque type OverlayId = String
object OverlayId extends Pattern[OverlayId]("overlay_id", "^O_[0-9]{1,8}$")

opaque type OfficialId = String
object OfficialId extends Pattern[OfficialId]("official_id", "^(REF|AR|FO|VAR)_[0-9]{2}$")

opaque type ReasonCode = String
object ReasonCode extends Pattern[ReasonCode]("why_it_matters.code", "^[a-z][a-z0-9_]{2,60}$")

opaque type MetricKey = String
object MetricKey extends Pattern[MetricKey]("metric", "^[a-z][a-z0-9_]{1,40}$")

opaque type HexColor = String
object HexColor extends Pattern[HexColor]("color", "^#[0-9a-fA-F]{6}$")

opaque type Formation = String
object Formation extends Pattern[Formation]("formation", "^[0-9](-[0-9]){2,4}$")

/** RFC 3339 date-time. Kept as the original string so re-encoding is byte-identical. */
opaque type Timestamp = String
object Timestamp
    extends Refined[Timestamp](
      "date-time",
      s => Try(OffsetDateTime.parse(s)).isSuccess,
      "RFC 3339 date-time"
    ):
  def of(i: Instant): Timestamp = i.toString
  extension (t: Timestamp) def toInstant: Instant = OffsetDateTime.parse(t).toInstant
