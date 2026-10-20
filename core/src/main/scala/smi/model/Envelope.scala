package smi.model

import io.circe.{Codec, Json}

/** Feed envelope. Only the shell is typed in the spike; payloads stay raw JSON until step 2. */
final case class Envelope(
    schemaVersion: String,
    seq: Long,
    matchId: String,
    source: String,
    op: String,
    emittedAt: String,
    payloadType: String,
    payload: Json
)

object Envelope:
  given Codec[Envelope] = Codec.forProduct8(
    "schema_version",
    "seq",
    "match_id",
    "source",
    "op",
    "emitted_at",
    "payload_type",
    "payload"
  )(Envelope.apply)(e =>
    (e.schemaVersion, e.seq, e.matchId, e.source, e.op, e.emittedAt, e.payloadType, e.payload)
  )
