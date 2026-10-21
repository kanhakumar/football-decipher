package smi.api

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import scala.concurrent.duration.*
import smi.model.*

/** Where a match's envelopes come from (ADR-016). `Replay`: the whole feed is generated up front
  * and released by the playhead (ADR-013). `Bus`: envelopes arrive one by one from a message bus
  * (Event Hubs in Azure), as they would from a live provider.
  */
enum FeedSource:
  case Replay, Bus

/** One envelope on the bus, tagged with its match run. `last` marks the end of the run. */
final case class BusMessage(runId: String, envelope: Envelope, last: Boolean, speed: Double)

/** A message bus for match feeds. Order within a run is preserved. */
trait MatchBus:
  def name: String
  def publish(batch: Vector[BusMessage]): IO[Unit]

  /** Subscribes from now on; the subscription is live once the resource is acquired. */
  def subscribe: Resource[IO, Stream[IO, BusMessage]]

/** The bus in memory: tests and `SMI_FEED=memory`. */
final class InMemoryBus private (topic: Topic[IO, BusMessage]) extends MatchBus:
  val name = "memory"
  // publish1 per message: the `publish` pipe would close the topic when the batch ends.
  def publish(batch: Vector[BusMessage]): IO[Unit] = batch.traverse_(topic.publish1)
  def subscribe: Resource[IO, Stream[IO, BusMessage]] = topic.subscribeAwait(100_000)

object InMemoryBus:
  def apply(): IO[InMemoryBus] = Topic[IO, BusMessage].map(new InMemoryBus(_))

/** Publishes a generated feed to the bus at playback pace: the provider side of a bus match. */
object Publisher:
  /** What the publisher reads each tick: the current speed, whether to keep going, and paused. */
  final case class Control(speed: Double, active: Boolean, paused: Boolean)

  def run(
      bus: MatchBus,
      runId: String,
      feed: Vector[Envelope],
      control: IO[Control],
      every: FiniteDuration = 20.millis
  ): IO[Unit] =
    val times = Hub.times(feed)
    def message(i: Int, speed: Double) = BusMessage(runId, feed(i), i == feed.size - 1, speed)
    for
      sent <- Ref[IO].of(0)
      clock <- Ref[IO].of(times.headOption.fold(0.0)(_.toDouble))
      start <- IO.monotonic
      _ <- Stream
        .fixedRate[IO](every)
        .evalMapAccumulate(start) { (last, _) =>
          IO.monotonic.flatMap { now =>
            control.flatMap { c =>
              val step = if c.paused then 0.0 else (now - last).toNanos / 1e6 * c.speed
              for
                at <- clock.updateAndGet(_ + step)
                from <- sent.get
                upTo = Iterator
                  .from(from)
                  .takeWhile(i => i < feed.size && times(i) <= at)
                  .size + from
                _ <- bus
                  .publish(Vector.range(from, upTo).map(message(_, c.speed)))
                  .whenA(upTo > from)
                _ <- sent.set(upTo)
              yield (now, c.active && upTo < feed.size)
            }
          }
        }
        .takeWhile(_._2)
        .compile
        .drain
    yield ()

/** Where insights and overlays are kept beyond the in-memory hub (Cosmos DB in Azure). */
trait MatchStore:
  def name: String
  def saveInsight(runId: String, i: Insight): IO[Unit]
  def saveOverlay(runId: String, o: Overlay): IO[Unit]

object MatchStore:
  val none: MatchStore = new MatchStore:
    val name = "none"
    def saveInsight(runId: String, i: Insight) = IO.unit
    def saveOverlay(runId: String, o: Overlay) = IO.unit

/** Keeps everything in memory: tests. */
final class InMemoryStore private (
    val insights: Ref[IO, Vector[(String, Insight)]],
    val overlays: Ref[IO, Vector[(String, Overlay)]]
) extends MatchStore:
  val name = "memory"
  def saveInsight(runId: String, i: Insight) = insights.update(_ :+ (runId -> i))
  def saveOverlay(runId: String, o: Overlay) = overlays.update(_ :+ (runId -> o))

object InMemoryStore:
  def apply(): IO[InMemoryStore] =
    for
      i <- Ref[IO].of(Vector.empty[(String, Insight)])
      o <- Ref[IO].of(Vector.empty[(String, Overlay)])
    yield new InMemoryStore(i, o)
