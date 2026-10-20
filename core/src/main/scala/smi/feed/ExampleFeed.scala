package smi.feed

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.parser.decode
import smi.model.Envelope

/** Replays the contract-pack examples as a stream of envelopes. */
object ExampleFeed:
  def fromNdjson(path: Path): Stream[IO, Envelope] =
    Files[IO]
      .readUtf8Lines(path)
      .filter(_.trim.nonEmpty)
      .evalMap(line => IO.fromEither(decode[Envelope](line)))
