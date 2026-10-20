package smi.model

import io.circe.*
import io.circe.syntax.*
import smi.model.Check.*

/** Reference to a record removed by an `op: delete` envelope. */
final case class DeleteRef(eventId: EventId, reason: Option[String] = None)
object DeleteRef:
  given Codec.AsObject[DeleteRef] = Wire.codec

/** What an envelope carries. Selected by `op` and `payload_type`, so it has no discriminator. */
sealed trait Payload
object Payload:
  final case class Meta(value: MatchMeta) extends Payload
  final case class Team(value: Lineup) extends Payload
  final case class Ev(value: Event) extends Payload
  final case class Frame(value: TrackingFrame) extends Payload
  final case class Delete(value: DeleteRef) extends Payload

  /** Undecoded payload, replaced by the envelope codec once `op` and `payload_type` are known. */
  final case class Raw(obj: JsonObject) extends Payload

  def decodeFor(op: Op, pt: PayloadType, obj: JsonObject): Either[String, Payload] =
    val json = Json.fromJsonObject(obj)
    def as[A: Decoder](wrap: A => Payload) =
      json.as[A].map(wrap).left.map(e => s"payload (${PayloadType.wire(pt)}): ${e.getMessage}")
    (op, pt) match
      case (Op.Delete, _)                 => as[DeleteRef](Delete(_))
      case (_, PayloadType.MatchMeta)     => as[MatchMeta](Meta(_))
      case (_, PayloadType.Lineup)        => as[Lineup](Team(_))
      case (_, PayloadType.Event)         => as[Event](Ev(_))
      case (_, PayloadType.TrackingFrame) => as[TrackingFrame](Frame(_))

  given Codec[Payload] = Codec.from(
    Decoder.decodeJsonObject.map(Raw(_)),
    Encoder.instance {
      case Meta(v)   => v.asJson
      case Team(v)   => v.asJson
      case Ev(v)     => v.asJson
      case Frame(v)  => v.asJson
      case Delete(v) => v.asJson
      case Raw(obj)  => Json.fromJsonObject(obj)
    }
  )

/** One message on the canonical feed. */
final case class Envelope(
    schemaVersion: String,
    seq: Long,
    matchId: MatchId,
    source: Source,
    op: Op,
    emittedAt: Timestamp,
    payloadType: PayloadType,
    payload: Payload
)

object Envelope:
  val SchemaVersion = "1.0"

  private def typePayload(e: Envelope): Either[String, Envelope] =
    e.payload match
      case Payload.Raw(obj) =>
        Payload.decodeFor(e.op, e.payloadType, obj).map(p => e.copy(payload = p))
      case _ => Right(e)

  def errors(e: Envelope): List[String] =
    that(e.schemaVersion == SchemaVersion, s"schema_version must be $SchemaVersion") ++
      atLeast("seq", e.seq.toDouble, 0)

  private val base: Codec.AsObject[Envelope] = Wire.codec

  given Codec.AsObject[Envelope] =
    Wire.checked(Codec.AsObject.from(base.emap(typePayload), base))(errors)
