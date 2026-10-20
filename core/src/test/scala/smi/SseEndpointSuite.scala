package smi

import cats.effect.IO
import fs2.io.file.Path
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import smi.api.SseRoutes
import smi.feed.ExampleFeed

class SseEndpointSuite extends CatsEffectSuite:
  private val app =
    SseRoutes(ExampleFeed.fromNdjson(Path("../schema/examples/examples.ndjson"))).orNotFound

  private def get(path: String) = app.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  test("/health returns ok") {
    get("/health").flatMap(r => r.as[String].map((r.status, _))).assertEquals((Status.Ok, "ok"))
  }

  test("/events/stream emits one SSE frame whose data is a schema-valid event envelope") {
    get("/events/stream").flatMap(_.as[String]).map { body =>
      val fields = body.linesIterator
        .filter(_.contains(":"))
        .map { l =>
          val (k, v) = l.splitAt(l.indexOf(':'))
          k -> v.drop(1).trim
        }
        .toMap
      assertEquals(fields.get("event"), Some("event"))
      assert(fields.contains("id"))
      val data = fields("data")
      assertEquals(SchemaFiles.errors(SchemaFiles.schema("canonical.schema.json"), data), Nil)
      assertEquals(parse(data).flatMap(_.hcursor.get[String]("payload_type")), Right("event"))
      assertEquals(body.split("\n\n").count(_.trim.nonEmpty), 1)
    }
  }
