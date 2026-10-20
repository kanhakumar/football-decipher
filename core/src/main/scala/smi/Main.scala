package smi

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import fs2.io.file.Path
import org.http4s.ember.server.EmberServerBuilder
import smi.api.SseRoutes
import smi.feed.ExampleFeed

object Main extends IOApp.Simple:
  private val examples = Path("../schema/examples/examples.ndjson")

  def run: IO[Unit] =
    for
      port <- IO(sys.env.get("SMI_PORT").flatMap(Port.fromString).getOrElse(port"8080"))
      app = SseRoutes(ExampleFeed.fromNdjson(examples)).orNotFound
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port)
        .withHttpApp(app)
        .build
        .evalTap(s => IO.println(s"smi-core listening on ${s.address}"))
        .useForever
    yield ()
