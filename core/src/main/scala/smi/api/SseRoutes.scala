package smi.api

import cats.effect.IO
import fs2.Stream
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*
import smi.model.Envelope

object SseRoutes:
  def toSse(e: Envelope): ServerSentEvent =
    ServerSentEvent(
      data = Some(e.asJson.noSpaces),
      eventType = Some(e.payloadType),
      id = Some(ServerSentEvent.EventId(e.seq.toString))
    )

  /** The spike streams only the first `event` envelope of the feed, then ends. */
  def apply(feed: Stream[IO, Envelope]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" => Ok("ok")
      case GET -> Root / "events" / "stream" =>
        Ok(feed.filter(_.payloadType == "event").take(1).map(toSse))
    }
