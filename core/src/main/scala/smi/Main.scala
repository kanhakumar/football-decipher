package smi

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.Uri
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Origin
import org.http4s.server.middleware.CORS
import smi.api.{Hub, InMemoryBus, Log, MatchBus, MatchStore, Routes}
import smi.cloud.{CosmosStore, EventHubsBus}

/** The core service: HTTP and SSE API over one match (ADR-013), fed by a replay or a bus (ADR-016).
  */
object Main extends IOApp.Simple:
  private val log = Log.console

  private def bus: IO[Option[MatchBus]] = Config.feed match
    case "replay" => IO.pure(None)
    case "memory" => InMemoryBus().map(Some(_))
    case "eventhubs" =>
      Config.eventHubs match
        case Some((namespace, hub)) => IO(Some(EventHubsBus(namespace, hub)))
        case None =>
          IO.raiseError(
            new IllegalArgumentException("SMI_FEED=eventhubs needs SMI_EVENTHUBS_NAMESPACE")
          )
    case other => IO.raiseError(new IllegalArgumentException(s"unknown SMI_FEED '$other'"))

  private def store: MatchStore = Config.cosmosEndpoint.fold(MatchStore.none)(CosmosStore(_))

  private def cors(app: org.http4s.HttpApp[IO]) =
    Config.corsOrigins match
      case List("*") => CORS.policy.withAllowOriginAll(app)
      case origins =>
        val hosts = origins.flatMap(o => Uri.fromString(o).toOption).flatMap { u =>
          for
            scheme <- u.scheme
            host <- u.host
          yield Origin.Host(scheme, host, u.port)
        }
        CORS.policy.withAllowOriginHost(hosts.toSet)(app)

  def run: IO[Unit] =
    val host = Host.fromString(Config.host).getOrElse(ipv4"127.0.0.1")
    val port = Port.fromInt(Config.port).getOrElse(port"8080")
    Resource
      .eval(bus)
      .flatMap { b =>
        val s = store
        Resource.eval(
          log.info(s"feed: ${b.fold("replay")(_.name)}, store: ${s.name}")
        ) >> Hub.resource(log, b, s)
      }
      .flatMap { hub =>
        // The viewer is served from another origin, so the API allows its origin (all locally).
        val app = Log.requests(log)(cors(Routes(hub).orNotFound))
        EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(app)
          .withShutdownTimeout(scala.concurrent.duration.Duration(1, "s"))
          .build
      }
      .evalTap(s => log.info(s"smi-core listening on ${s.address}"))
      .useForever
