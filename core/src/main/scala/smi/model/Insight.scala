package smi.model

import io.circe.{Codec, JsonObject}
import smi.model.Check.*

final case class WhyItMatters(code: ReasonCode, params: JsonObject)
object WhyItMatters:
  given Codec.AsObject[WhyItMatters] = Wire.codec

/** Central contract between engine, agents and delivery: facts and a reason code, never prose. */
final case class Insight(
    insightId: InsightId,
    matchId: MatchId,
    kind: InsightKind,
    period: Int,
    tMatchS: Double,
    videoTsMs: Long,
    teams: List[TeamId],
    players: Option[List[PlayerId]] = None,
    /** Every number narration may use. Values are numbers, strings, booleans or arrays. */
    facts: JsonObject,
    evidenceEventIds: List[EventId],
    whyItMatters: WhyItMatters,
    importance: Double,
    basis: Basis,
    capabilityLevel: Int,
    createdAt: Timestamp,
    status: Option[InsightStatus] = None,
    supersedes: Option[InsightId] = None
)

object Insight:
  def errors(i: Insight): List[String] =
    range("period", i.period, 1, 5) ++ atLeast("t_match_s", i.tMatchS, 0) ++
      atLeast("video_ts_ms", i.videoTsMs.toDouble, 0) ++ size("teams", i.teams.size, 1, 2) ++
      that(i.facts.nonEmpty, "facts must not be empty") ++
      i.facts.toList.flatMap((k, v) =>
        that(v.isNumber || v.isString || v.isBoolean || v.isArray, s"facts.$k has unsupported type")
      ) ++
      that(i.evidenceEventIds.nonEmpty, "evidence_event_ids must not be empty") ++
      that(
        i.evidenceEventIds.distinct.size == i.evidenceEventIds.size,
        "evidence_event_ids must be unique"
      ) ++
      range("importance", i.importance, 0, 1) ++ range("capability_level", i.capabilityLevel, 0, 2)

  given Codec.AsObject[Insight] = Wire.checked(Wire.codec[Insight])(errors)
