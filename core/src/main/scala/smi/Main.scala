package smi

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import smi.api.{Hub, Log, Routes}

/** The core service: HTTP and SSE API over one replayed match (ADR-013). */
object Main extends IOApp.Simple:
  def run: IO[Unit] =
    val port = sys.env.get("SMI_PORT").flatMap(Port.fromString).getOrElse(port"8080")
    Hub
      .resource(Log.console)
      .flatMap { hub =>
        // The viewer is served from another origin, so the API allows cross-origin reads.
        val app = Log.requests(Log.console)(CORS.policy.withAllowOriginAll(Routes(hub).orNotFound))
        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port)
          .withHttpApp(app)
          .withShutdownTimeout(scala.concurrent.duration.Duration(1, "s"))
          .build
      }
      .evalTap(s => Log.console.info(s"smi-core listening on ${s.address}"))
      .useForever
