package smi.raw

import io.circe.Codec
import scala.collection.immutable.ListMap
import smi.model.{Check, Timestamp, Wire, WireEnum}

// The provider-style raw feed ("synthfeed"). Shapes are ours; see registry.yaml and ADR-009.

enum RawKind:
  case Match, Lineup, Event
object RawKind extends WireEnum[RawKind]("kind"):
  def all = values

enum RawAction:
  case Upsert, Delete
object RawAction extends WireEnum[RawAction]("action"):
  def all = values

final case class RawQualifier(qid: Int, value: String)
object RawQualifier:
  given Codec.AsObject[RawQualifier] = Wire.codec

final case class RawEvent(
    id: Long,
    typeId: Int,
    periodId: Int,
    /** Match minute as on a broadcast clock: the second half starts at 45. */
    min: Int,
    sec: Int,
    timestamp: Timestamp,
    teamId: Int,
    playerId: Option[Long] = None,
    /** 0..100 on both axes, team-relative. */
    x: Option[Double] = None,
    y: Option[Double] = None,
    outcome: Option[Int] = None,
    qualifiers: List[RawQualifier] = Nil
):
  def qualifier(qid: Int): Option[String] = qualifiers.find(_.qid == qid).map(_.value)

object RawEvent:
  given Codec.AsObject[RawEvent] = Wire.checked(Wire.codec[RawEvent]) { e =>
    Check.range("period_id", e.periodId, 1, 5) ++ Check.range("sec", e.sec, 0, 59) ++
      Check.atLeast("min", e.min, 0) ++ Check.opt(e.outcome)(o =>
        Check.that(o == 0 || o == 1, s"outcome $o")
      )
  }

final case class RawMatchInfo(
    competition: String,
    season: String,
    kickoff: Timestamp,
    statusId: Int,
    homeTeamId: Int,
    awayTeamId: Int,
    pitchLengthM: Double,
    pitchWidthM: Double,
    /** Goals keyed by provider team ID. */
    score: ListMap[String, Int]
)
object RawMatchInfo:
  import Wire.given
  given Codec.AsObject[RawMatchInfo] = Wire.codec

final case class RawPlayer(
    id: Long,
    name: String,
    shirt: Int,
    position: String,
    starter: Boolean,
    slot: Option[String] = None
)
object RawPlayer:
  given Codec.AsObject[RawPlayer] = Wire.codec

final case class RawLineup(
    teamId: Int,
    name: String,
    short: String,
    colors: Option[List[String]] = None,
    formation: String,
    players: List[RawPlayer]
)
object RawLineup:
  given Codec.AsObject[RawLineup] = Wire.codec

/** One message on the raw feed. Exactly one body is set, chosen by `kind` and `action`. */
final case class RawMessage(
    kind: RawKind,
    action: RawAction,
    matchRef: String,
    lastModified: Timestamp,
    matchInfo: Option[RawMatchInfo] = None,
    lineup: Option[RawLineup] = None,
    event: Option[RawEvent] = None,
    eventId: Option[Long] = None
):
  /** The provider event ID this message is about, if it is an event message. */
  def eventRef: Option[Long] = event.map(_.id).orElse(eventId)

object RawMessage:
  def errors(m: RawMessage): List[String] =
    val bodies = List(m.matchInfo, m.lineup, m.event, m.eventId).count(_.isDefined)
    val expected = (m.kind, m.action) match
      case (RawKind.Match, RawAction.Upsert)  => m.matchInfo.isDefined
      case (RawKind.Lineup, RawAction.Upsert) => m.lineup.isDefined
      case (RawKind.Event, RawAction.Upsert)  => m.event.isDefined
      case (RawKind.Event, RawAction.Delete)  => m.eventId.isDefined
      case _                                  => false
    Check.that(
      bodies == 1 && expected,
      s"${RawKind.wire(m.kind)}/${RawAction.wire(m.action)} body mismatch"
    )

  given Codec.AsObject[RawMessage] = Wire.checked(Wire.codec[RawMessage])(errors)
