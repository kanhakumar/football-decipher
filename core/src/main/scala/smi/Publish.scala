package smi

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.UUIDGen
import smi.api.{Hub, MatchConfig, Publisher}
import smi.cloud.EventHubsBus

/** The generator job: publishes one match to Event Hubs at playback pace, like a live provider. A
  * core running with SMI_FEED=eventhubs picks the run up and plays it (ADR-016).
  *
  * Usage: publish --seed 154 [--speed 1] [--tracking] (needs SMI_EVENTHUBS_NAMESPACE)
  */
object Publish extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    def arg(name: String) = args.sliding(2).collectFirst { case List(`name`, v) => v }
    val cfg = MatchConfig(
      seed = arg("--seed").flatMap(_.toLongOption).getOrElse(154L),
      speed = arg("--speed").flatMap(_.toDoubleOption).getOrElse(1.0),
      tracking = args.contains("--tracking")
    )
    Config.eventHubs match
      case None =>
        IO.println("SMI_EVENTHUBS_NAMESPACE is not set").as(ExitCode.Error)
      case Some((namespace, hub)) =>
        val bus = EventHubsBus(namespace, hub)
        for
          runId <- UUIDGen[IO].randomUUID.map(_.toString)
          feed <- IO.blocking(Hub.feed(cfg))
          _ <- IO.println(s"publishing seed ${cfg.seed} at ${cfg.speed}x to $hub: run $runId")
          _ <- Publisher.run(bus, runId, feed, IO.pure(Publisher.Control(cfg.speed, true, false)))
          _ <- IO.println(s"published ${feed.size} envelopes")
        yield ExitCode.Success
