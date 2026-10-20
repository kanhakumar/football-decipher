package smi

import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import munit.FunSuite
import smi.model.Envelope

/** Golden round trip: decode then re-encode every example envelope and compare to the original. */
class EnvelopeGoldenSuite extends FunSuite:
  test("examples.ndjson round-trips through Envelope unchanged") {
    SchemaFiles.ndjson("examples/examples.ndjson").zipWithIndex.foreach { (line, i) =>
      val original = parse(line).fold(throw _, identity)
      val env = decode[Envelope](line).fold(throw _, identity)
      assertEquals(env.asJson, original, s"line ${i + 1}")
    }
  }

  test("an envelope missing a required field fails to decode") {
    val noSeq =
      """{"schema_version":"1.0","match_id":"M","source":"synthetic","op":"create","emitted_at":"2026-10-18T15:00:00.000Z","payload_type":"event","payload":{}}"""
    assert(decode[Envelope](noSeq).isLeft)
  }
