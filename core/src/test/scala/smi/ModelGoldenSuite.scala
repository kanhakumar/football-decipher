package smi

import io.circe.{Decoder, Encoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import smi.model.*

/** Golden tests: the hand-written models must agree with /schema exactly (ADR-003). */
class ModelGoldenSuite extends FunSuite:
  private final case class Model(decode: String => Either[String, Json])

  private def model[A: Decoder: Encoder]: Model =
    Model(s => decode[A](s).map(_.asJson).left.map(_.getMessage))

  private val modelForExample: Map[String, Model] = Map(
    "examples.ndjson" -> model[Envelope],
    "insight.example.json" -> model[Insight],
    "overlay.example.json" -> model[Overlay],
    "overlay-shape.example.json" -> model[Overlay],
    "insight-full-time.example.json" -> model[Insight],
    "overlay-recap.example.json" -> model[Overlay]
  )

  test("every file in /schema/examples has a model") {
    assertEquals(SchemaFiles.exampleFiles.filterNot(modelForExample.contains), Nil)
  }

  test("every example decodes and re-encodes to the same JSON") {
    for
      file <- SchemaFiles.exampleFiles
      (doc, i) <- SchemaFiles.exampleDocs(file).zipWithIndex
    do
      val reencoded = modelForExample(file).decode(doc)
      assertEquals(reencoded, Right(SchemaFiles.json(doc)), s"$file #${i + 1}")
  }

  test("re-encoded examples still conform to the schema") {
    for
      file <- SchemaFiles.exampleFiles
      doc <- SchemaFiles.exampleDocs(file)
    do
      val out = modelForExample(file).decode(doc).toOption.get.noSpaces
      val schema = SchemaFiles.schema(SchemaFiles.schemaForExample(file))
      assertEquals(SchemaFiles.errors(schema, out), Nil, file)
  }

  test("the decoders reject every negative case") {
    SchemaFiles.negativeCases.foreach { c =>
      assert(modelForExample(c.file).decode(c.doc).isLeft, s"decoder accepted '${c.name}'")
    }
  }

  test("payloads and event details decode to their typed shapes") {
    val envs = SchemaFiles.exampleDocs("examples.ndjson").map(decode[Envelope](_).toOption.get)
    val kinds = envs.map(_.payload.getClass.getSimpleName)
    assertEquals(kinds, List("Meta", "Team", "Ev", "Frame", "Delete"))
    val pass = envs.collectFirst { case Envelope(_, _, _, _, _, _, _, Payload.Ev(e)) => e }.get
    assert(pass.details.exists(_.isInstanceOf[PassDetails]), pass.details.toString)
  }
