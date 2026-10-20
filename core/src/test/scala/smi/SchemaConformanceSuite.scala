package smi

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import munit.FunSuite

/** Scala twin of schema/validate_examples.py: examples must conform, known-bad variants must not.
  */
class SchemaConformanceSuite extends FunSuite:
  private val canonical = SchemaFiles.schema("canonical.schema.json")
  private val insight = SchemaFiles.schema("insight.schema.json")
  private val overlay = SchemaFiles.schema("overlay.schema.json")

  private def json(s: String): Json = parse(s).fold(throw _, identity)
  private val lines = SchemaFiles.ndjson("examples/examples.ndjson").map(json)

  private def mutate(doc: Json)(f: JsonObject => JsonObject): String =
    doc.mapObject(f).noSpaces

  private def firstOfType(payloadType: String): Json =
    lines.find(_.hcursor.get[String]("payload_type").contains(payloadType)).get

  test("every line of examples.ndjson conforms to the canonical schema") {
    SchemaFiles.ndjson("examples/examples.ndjson").zipWithIndex.foreach { (line, i) =>
      assertEquals(SchemaFiles.errors(canonical, line), Nil, s"line ${i + 1}")
    }
  }

  test("insight and overlay examples conform") {
    assertEquals(
      SchemaFiles.errors(insight, SchemaFiles.readString("examples/insight.example.json")),
      Nil
    )
    assertEquals(
      SchemaFiles.errors(overlay, SchemaFiles.readString("examples/overlay.example.json")),
      Nil
    )
  }

  test("known-bad envelope variants are rejected") {
    val event = firstOfType("event")
    val bad = List(
      "wrong schema_version" -> mutate(event)(_.add("schema_version", Json.fromString("2.0"))),
      "negative seq" -> mutate(event)(_.add("seq", Json.fromInt(-1))),
      "unknown source" -> mutate(event)(_.add("source", Json.fromString("vendor"))),
      "missing payload" -> mutate(event)(_.remove("payload")),
      "extra envelope field" -> mutate(event)(_.add("extra", Json.fromInt(1))),
      "bad emitted_at" -> mutate(event)(_.add("emitted_at", Json.fromString("yesterday"))),
      "x beyond pitch" -> mutate(event)(
        _.add(
          "payload",
          event.hcursor
            .downField("payload")
            .focus
            .get
            .mapObject(_.add("x", Json.fromDouble(105.5).get))
        )
      )
    )
    bad.foreach((name, doc) => assert(SchemaFiles.errors(canonical, doc).nonEmpty, name))
  }

  test("known-bad insight and overlay variants are rejected") {
    val ins = json(SchemaFiles.readString("examples/insight.example.json"))
    val ovl = json(SchemaFiles.readString("examples/overlay.example.json"))
    assert(
      SchemaFiles.errors(insight, mutate(ins)(_.add("text", Json.fromString("prose")))).nonEmpty
    )
    assert(
      SchemaFiles
        .errors(insight, mutate(ins)(_.add("importance", Json.fromDouble(1.5).get)))
        .nonEmpty
    )
    assert(SchemaFiles.errors(overlay, mutate(ovl)(_.add("op", Json.fromString("hide")))).nonEmpty)
    assert(SchemaFiles.errors(overlay, mutate(ovl)(_.remove("t_start_ms"))).nonEmpty)
  }
