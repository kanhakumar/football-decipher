package smi.model

import io.circe.*
import io.circe.syntax.*
import smi.Pitch
import smi.model.Check.*
import scala.collection.immutable.ListMap

final case class SourceIds(
    provider: Option[String] = None,
    eventId: Option[String] = None,
    playerId: Option[String] = None,
    teamId: Option[String] = None
)
object SourceIds:
  given Codec.AsObject[SourceIds] = Wire.codec

final case class Event(
    eventId: EventId,
    `type`: EventType,
    period: Int,
    periodClockS: Double,
    matchClockS: Double,
    wallclockUtc: Timestamp,
    videoTsMs: Long,
    teamId: TeamId,
    playerId: Option[PlayerId] = None,
    x: Option[Double] = None,
    y: Option[Double] = None,
    possessionId: Option[PossessionId] = None,
    sequenceIndex: Option[Int] = None,
    underPressure: Option[Boolean] = None,
    outcome: Option[Outcome] = None,
    relatedEventIds: Option[List[EventId]] = None,
    details: Option[EventDetails] = None,
    sourceIds: Option[SourceIds] = None,
    provenance: Option[ListMap[String, Provenance]] = None,
    extras: Option[JsonObject] = None
)

object Event:
  import Outcome.*
  import Wire.given

  private val passOutcomes = Set(Complete, Incomplete, Out, Offside, Intercepted)
  private val shotOutcomes = Set(Goal, Saved, Blocked, OffTarget, Post)
  private val duelOutcomes = Set(Won, Lost)

  /** Per-type rules from the schema's if/then branches: required fields and allowed outcomes. */
  private final case class Rule(
      player: Boolean = false,
      location: Boolean = false,
      outcomes: Option[Set[Outcome]] = None,
      details: Boolean = false
  )

  private def rule(t: EventType): Rule =
    import EventType.*
    t match
      case Pass  => Rule(player = true, location = true, Some(passOutcomes), details = true)
      case Shot  => Rule(player = true, location = true, Some(shotOutcomes), details = true)
      case Carry => Rule(player = true, location = true, details = true)
      case Dribble | Tackle => Rule(player = true, location = true, Some(duelOutcomes))
      case Interception | Clearance | Block | BallReceipt | Offside =>
        Rule(player = true, location = true)
      case Pressure | Foul | GoalkeeperAction => Rule(player = true, details = true)
      case PossessionChange | SetPiece | Substitution | VarReview => Rule(details = true)
      case PeriodStart | PeriodEnd                                => Rule()

  def errors(e: Event): List[String] =
    val r = rule(e.`type`)
    val t = EventType.wire(e.`type`)
    range("period", e.period, 1, 5) ++
      atLeast("period_clock_s", e.periodClockS, 0) ++
      atLeast("match_clock_s", e.matchClockS, 0) ++
      atLeast("video_ts_ms", e.videoTsMs.toDouble, 0) ++
      opt(e.x)(range("x", _, 0, Pitch.Length)) ++
      opt(e.y)(range("y", _, 0, Pitch.Width)) ++
      opt(e.sequenceIndex)(i => atLeast("sequence_index", i, 0)) ++
      that(!r.player || e.playerId.isDefined, s"$t requires player_id") ++
      that(!r.location || (e.x.isDefined && e.y.isDefined), s"$t requires x and y") ++
      that(r.outcomes.isEmpty || e.outcome.isDefined, s"$t requires outcome") ++
      that(!r.details || e.details.isDefined, s"$t requires details") ++
      opt(r.outcomes.zip(e.outcome)) { (allowed, o) =>
        that(allowed(o), s"outcome '${Outcome.wire(o)}' not allowed for $t")
      }

  /** Replaces raw details with the typed shape the event type selects. */
  private def typeDetails(e: Event): Either[String, Event] =
    e.details match
      case Some(EventDetails.Raw(obj)) =>
        EventDetails.decodeFor(e.`type`, obj).map(d => e.copy(details = Some(d)))
      case _ => Right(e)

  private val base: Codec.AsObject[Event] = Wire.codec

  given Codec.AsObject[Event] =
    Wire.checked(Codec.AsObject.from(base.emap(typeDetails), base))(errors)

/** The `details` object. Its shape depends on the event type, so it carries no discriminator. */
sealed trait EventDetails

object EventDetails:
  /** Details for types the schema leaves open (period_start, period_end). */
  final case class Raw(obj: JsonObject) extends EventDetails

  def decodeFor(t: EventType, obj: JsonObject): Either[String, EventDetails] =
    import EventType.*
    val json = Json.fromJsonObject(obj)
    def as[D <: EventDetails: Decoder]: Either[String, EventDetails] =
      json.as[D].left.map(e => s"details for ${EventType.wire(t)}: ${e.getMessage}")
    t match
      case Pass                                                     => as[PassDetails]
      case Shot                                                     => as[ShotDetails]
      case Carry                                                    => as[CarryDetails]
      case Dribble | Tackle                                         => as[TargetDetails]
      case Interception | Clearance | Block | BallReceipt | Offside => as[GenericDetails]
      case Pressure                                                 => as[PressureDetails]
      case PossessionChange                                         => as[PossessionChangeDetails]
      case Foul                                                     => as[FoulDetails]
      case SetPiece                                                 => as[SetPieceDetails]
      case GoalkeeperAction                                         => as[GkDetails]
      case Substitution                                             => as[SubstitutionDetails]
      case VarReview                                                => as[VarDetails]
      case PeriodStart | PeriodEnd                                  => Right(Raw(obj))

  /** Decoding keeps details raw; `Event` types them once it knows the event type. */
  given Codec[EventDetails] = Codec.from(
    Decoder.decodeJsonObject.map(Raw(_)),
    Encoder.instance {
      case d: PassDetails             => d.asJson
      case d: ShotDetails             => d.asJson
      case d: CarryDetails            => d.asJson
      case d: TargetDetails           => d.asJson
      case d: GenericDetails          => d.asJson
      case d: PressureDetails         => d.asJson
      case d: PossessionChangeDetails => d.asJson
      case d: FoulDetails             => d.asJson
      case d: SetPieceDetails         => d.asJson
      case d: GkDetails               => d.asJson
      case d: SubstitutionDetails     => d.asJson
      case d: VarDetails              => d.asJson
      case Raw(obj)                   => Json.fromJsonObject(obj)
    }
  )

final case class PassDetails(
    endX: Double,
    endY: Double,
    lengthM: Double,
    angleRad: Option[Double] = None,
    height: Option[PassHeight] = None,
    bodyPart: Option[BodyPart] = None,
    recipientId: Option[PlayerId] = None,
    passType: Option[PassType] = None,
    isThroughBall: Option[Boolean] = None,
    isCross: Option[Boolean] = None,
    isSwitch: Option[Boolean] = None,
    durationS: Option[Double] = None
) extends EventDetails
object PassDetails:
  given Codec.AsObject[PassDetails] = Wire.checked(Wire.codec[PassDetails]) { d =>
    range("end_x", d.endX, 0, Pitch.Length) ++ range("end_y", d.endY, 0, Pitch.Width) ++
      atLeast("length_m", d.lengthM, 0) ++ opt(d.angleRad)(
        range("angle_rad", _, -3.1416, 3.1416)
      ) ++
      opt(d.durationS)(atLeast("duration_s", _, 0))
  }

final case class FreezeFramePlayer(playerId: PlayerId, teamId: TeamId, x: Double, y: Double)
object FreezeFramePlayer:
  given Codec.AsObject[FreezeFramePlayer] = Wire.checked(Wire.codec[FreezeFramePlayer]) { p =>
    Tracking.xyErrors(p.x, p.y)
  }

final case class ShotDetails(
    bodyPart: Option[BodyPart] = None,
    situation: ShotSituation,
    shotSpeedKmh: Option[Double] = None,
    goalY: Option[Double] = None,
    goalZ: Option[Double] = None,
    assistEventId: Option[EventId] = None,
    isFirstTime: Option[Boolean] = None,
    freezeFrame: Option[List[FreezeFramePlayer]] = None
) extends EventDetails
object ShotDetails:
  given Codec.AsObject[ShotDetails] = Wire.checked(Wire.codec[ShotDetails]) { d =>
    opt(d.shotSpeedKmh)(range("shot_speed_kmh", _, 0, 200)) ++
      opt(d.goalY)(range("goal_y", _, 0, 7.32)) ++ opt(d.goalZ)(range("goal_z", _, 0, 2.44))
  }

final case class CarryDetails(
    endX: Double,
    endY: Double,
    distanceM: Double,
    durationS: Double,
    topSpeedKmh: Option[Double] = None
) extends EventDetails
object CarryDetails:
  given Codec.AsObject[CarryDetails] = Wire.checked(Wire.codec[CarryDetails]) { d =>
    range("end_x", d.endX, 0, Pitch.Length) ++ range("end_y", d.endY, 0, Pitch.Width) ++
      atLeast("distance_m", d.distanceM, 0) ++ above("duration_s", d.durationS, 0) ++
      opt(d.topSpeedKmh)(range("top_speed_kmh", _, 0, 45))
  }

final case class TargetDetails(targetPlayerId: Option[PlayerId] = None) extends EventDetails
object TargetDetails:
  given Codec.AsObject[TargetDetails] = Wire.codec

final case class GenericDetails(
    bodyPart: Option[BodyPart] = None,
    fromEventId: Option[EventId] = None
) extends EventDetails
object GenericDetails:
  given Codec.AsObject[GenericDetails] = Wire.codec

final case class PressureDetails(
    pressedPlayerId: PlayerId,
    durationS: Option[Double] = None,
    isCounterpress: Option[Boolean] = None
) extends EventDetails
object PressureDetails:
  given Codec.AsObject[PressureDetails] = Wire.checked(Wire.codec[PressureDetails]) { d =>
    opt(d.durationS)(atLeast("duration_s", _, 0))
  }

final case class PossessionChangeDetails(fromTeam: TeamId, toTeam: TeamId, cause: PossessionCause)
    extends EventDetails
object PossessionChangeDetails:
  given Codec.AsObject[PossessionChangeDetails] = Wire.codec

final case class FoulDetails(
    fouledPlayerId: PlayerId,
    card: Card,
    isPenalty: Option[Boolean] = None
) extends EventDetails
object FoulDetails:
  given Codec.AsObject[FoulDetails] = Wire.codec

final case class SetPieceDetails(kind: SetPieceKind) extends EventDetails
object SetPieceDetails:
  given Codec.AsObject[SetPieceDetails] = Wire.codec

final case class GkDetails(action: GkAction) extends EventDetails
object GkDetails:
  given Codec.AsObject[GkDetails] = Wire.codec

final case class SubstitutionDetails(playerOffId: PlayerId, playerOnId: PlayerId)
    extends EventDetails
object SubstitutionDetails:
  given Codec.AsObject[SubstitutionDetails] = Wire.codec

final case class VarDetails(decision: VarDecision, reviewedEventId: Option[EventId] = None)
    extends EventDetails
object VarDetails:
  given Codec.AsObject[VarDetails] = Wire.codec
