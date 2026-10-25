package smi

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.Uri
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Origin
import org.http4s.server.middleware.CORS
import scala.concurrent.duration.*
import smi.api.{Auth, EntraTokens, Hub, InMemoryBus, Log, MatchBus, MatchStore, Routes, SettingsHub}
import smi.cloud.{AppConfigSource, CosmosStore, EventHubsBus}

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

  /** Origins from the settings when App Configuration sets them (ADR-019), else SMI_CORS_ORIGINS;
    * read per request so a change applies without a restart.
    */
  private def cors(settings: SettingsHub)(app: org.http4s.HttpApp[IO]): org.http4s.HttpApp[IO] =
    cats.data.Kleisli { req =>
      settings.get.flatMap { s =>
        val origins =
          if s.fromStore("access:cors_origins") then s.corsOrigins else Config.corsOrigins
        corsFor(origins)(app).run(req)
      }
    }

  private def corsFor(origins: List[String])(app: org.http4s.HttpApp[IO]) =
    origins match
      case List("*") => CORS.policy.withAllowOriginAll(app)
      case origins =>
        val hosts = origins.flatMap(o => Uri.fromString(o).toOption).flatMap { u =>
          for
            scheme <- u.scheme
            host <- u.host
          yield Origin.Host(scheme, host, u.port)
        }
        CORS.policy.withAllowOriginHost(hosts.toSet)(app)

  /** The API gate (ADR-018), or the routes as they are when SMI_AUTH is off. */
  private def gate(settings: SettingsHub)(app: org.http4s.HttpApp[IO]): IO[org.http4s.HttpApp[IO]] =
    if !Config.authOn then log.info("auth: off").as(app)
    else if Config.apiKey.isEmpty && Config.authAudience.isEmpty then
      IO.raiseError(IllegalArgumentException("SMI_AUTH=on needs SMI_API_KEY or SMI_AUTH_AUDIENCE"))
    else
      val tokens = Config.authAudience.map(EntraTokens.remote)
      val key = if Config.apiKey.isDefined then "set" else "unset"
      val signIn = if tokens.isDefined then "on" else "off"
      log
        .info(s"auth: on (API key $key, sign-in $signIn)")
        .as(Auth(Config.apiKey, tokens, settings.get.map(_.signInRequired))(app))

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
        // Settings (ADR-019): config/app.yaml, overridden from App Configuration every 2 minutes.
        val source = Config.appConfigEndpoint.map(AppConfigSource(_))
        Resource
          .eval(SettingsHub(source, log))
          .evalTap(_ => log.info(s"settings: ${source.fold("config/app.yaml")(_.name)}"))
          .flatTap(sh => sh.refreshEvery(2.minutes).background)
          .map(hub -> _)
      }
      .flatMap { (hub, settings) =>
        // The viewer is served from another origin, so the API allows its origin (all locally).
        // CORS is outermost, so preflights and refusals both carry its headers.
        Resource
          .eval(gate(settings)(Routes(hub, settings).orNotFound))
          .map(g => Log.requests(log)(cors(settings)(g)))
      }
      .flatMap { app =>
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
