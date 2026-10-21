package smi.api

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import fs2.Stream
import fs2.concurrent.SignallingRef
import scala.concurrent.duration.*
import smi.generator.{GeneratorConfig, MatchGenerator}
import smi.insight.{MomentState, Moments}
import smi.model.*
import smi.tracking.{SynthConfig, TrackingSynth}

enum Status:
  case Idle, Running, Paused, Finished
object Status extends WireEnum[Status]("status"):
  def all = values

final case class MatchConfig(
    seed: Long = 154,
    speed: Double = 1,
    delayS: Double = 10,
    tracking: Boolean = false
)

object MatchConfig:
  /** Up to 600x so tests and rehearsals can run a whole match in seconds. */
  val Speeds: (Double, Double) = (0.25, 600.0)
  val Delays: (Double, Double) = (0.0, 60.0)

  def validate(c: MatchConfig): Either[String, MatchConfig] =
    if c.speed < Speeds._1 || c.speed > Speeds._2 then
      Left(s"speed ${c.speed} must be ${Speeds._1} to ${Speeds._2}")
    else if c.delayS < Delays._1 || c.delayS > Delays._2 then
      Left(s"delay_s ${c.delayS} must be ${Delays._1} to ${Delays._2}")
    else Right(c)

/** One replayed match. `live` is the generator's position (engine and detectors run here); `viewer`
  * trails it by the viewer delay (ADR-013, D5).
  */
final case class Playback(
    generation: Long = 0,
    config: MatchConfig = MatchConfig(),
    status: Status = Status.Idle,
    feed: Vector[Envelope] = Vector.empty,
    /** Milliseconds since kickoff of each envelope, from its emitted_at. */
    times: Vector[Long] = Vector.empty,
    live: Int = 0,
    viewer: Int = 0,
    clockMs: Double = 0,
    moments: MomentState = MomentState(),
    /** Every insight version, in emission order (new, retracted, superseding). */
    insights: Vector[Insight] = Vector.empty,
    overlays: Vector[Overlay] = Vector.empty,
    overlayFor: Map[InsightId, OverlayId] = Map.empty,
    /** Set once the detectors have been closed at the end of the feed. */
    closed: Boolean = false
):
  def matchId: Option[MatchId] = feed.headOption.map(_.matchId)

  /** Advances the playhead by `realMs` of wall time at the configured speed. */
  def tick(realMs: Double): Playback =
    if status != Status.Running then this
    else
      val clock = clockMs + realMs * config.speed
      val delay = config.delayS * 1000
      var v = viewer
      while v < feed.size && times(v) <= clock - delay do v += 1
      val done = v == feed.size
      feedLive(clock).closeDetectors
        .copy(clockMs = clock, viewer = v, status = if done then Status.Finished else status)

  private def feedLive(clock: Double): Playback =
    var p = this
    var i = live
    while i < feed.size && times(i) <= clock do
      p = p.process(Moments.step(p.moments, feed(i)))
      i += 1
    p.copy(live = i)

  /** Closes the detectors once the live cursor has passed the last envelope. */
  private def closeDetectors: Playback =
    if live == feed.size && feed.nonEmpty && !closed then
      process(Moments.finish(moments)).copy(closed = true)
    else this

  /** Takes the detectors' new state, recording new insights and the overlays they imply. */
  private def process(next: MomentState): Playback =
    val fresh = next.out.drop(moments.out.size)
    fresh.foldLeft(copy(moments = next))((p, i) => p.withInsight(i))

  private def withInsight(i: Insight): Playback =
    val recorded = copy(insights = insights :+ i)
    val retracted = i.status.contains(InsightStatus.Retracted)
    (i.kind, retracted, overlayFor.get(i.insightId)) match
      case (InsightKind.Goal, false, None) =>
        recorded.publish(
          Overlays.scoreline(i, recorded.overlays.size + 1, scoreAfter),
          Some(i.insightId)
        )
      case (_, true, Some(id)) => recorded.publish(Overlays.retract(id, i), None)
      case _                   => recorded

  private def scoreAfter: String =
    val score = moments.engine.derived.score
    val teams = moments.engine.teams
    val short = moments.engine.lineups.map((t, l) => t -> l.short)
    teams.map(t => s"${short.getOrElse(t, t.value)} ${score.getOrElse(t, 0)}").mkString(" - ")

  def publish(o: Overlay, forInsight: Option[InsightId]): Playback =
    copy(
      overlays = overlays :+ o,
      overlayFor = forInsight.fold(overlayFor)(id => overlayFor.updated(id, o.overlayId))
    )

/** The core's one live match: control, playback ticker and the state every route reads. */
final class Hub(val state: SignallingRef[IO, Playback], lock: Mutex[IO]):
  def snapshot: IO[Playback] = state.get

  def start(c: MatchConfig): IO[Either[String, Playback]] =
    MatchConfig.validate(c) match
      case Left(err) => IO.pure(Left(err))
      case Right(cfg) =>
        lock.lock.surround {
          for
            feed <- IO.blocking(Hub.feed(cfg))
            times = Hub.times(feed)
            prev <- state.get
            next = Playback(
              prev.generation + 1,
              cfg,
              Status.Running,
              feed,
              times,
              clockMs = times.headOption.getOrElse(0L).toDouble
            )
            _ <- state.set(next)
          yield Right(next)
        }

  def pause: IO[Playback] = state.updateAndGet(p =>
    if p.status == Status.Running then p.copy(status = Status.Paused) else p
  )
  def resume: IO[Playback] = state.updateAndGet(p =>
    if p.status == Status.Paused then p.copy(status = Status.Running) else p
  )

  def speed(s: Double): IO[Either[String, Playback]] =
    state.get.flatMap { p =>
      MatchConfig.validate(p.config.copy(speed = s)) match
        case Left(err) => IO.pure(Left(err))
        case Right(c)  => state.updateAndGet(_.copy(config = c)).map(Right(_))
    }

  /** Accepts an overlay from the agent service (it must decode strictly, which validates it). */
  def addOverlay(o: Overlay): IO[Either[String, Overlay]] =
    state.modify { p =>
      if p.matchId.forall(_ != o.matchId) then
        (p, Left(s"overlay is for ${o.matchId.value}, not the current match"))
      else if p.overlays.exists(x => x.overlayId == o.overlayId && x.op == o.op) then
        (p, Left(s"${o.overlayId.value} already published"))
      else (p.publish(o, None), Right(o))
    }

  /** Runs the playhead: every tick advances by the real time that passed. */
  def ticker(every: FiniteDuration = 20.millis): Stream[IO, Unit] =
    Stream.eval(IO.monotonic).flatMap { start =>
      Stream
        .fixedRate[IO](every)
        .evalMapAccumulate(start) { (last, _) =>
          IO.monotonic.flatMap { now =>
            lock.lock.surround(state.update(_.tick((now - last).toNanos / 1e6))).as((now, ()))
          }
        }
        .as(())
    }

object Hub:
  def resource: Resource[IO, Hub] =
    for
      ref <- Resource.eval(SignallingRef[IO].of(Playback()))
      lock <- Resource.eval(Mutex[IO])
      hub = Hub(ref, lock)
      _ <- hub.ticker().compile.drain.background
    yield hub

  def feed(c: MatchConfig): Vector[Envelope] =
    val events = MatchGenerator.generate(GeneratorConfig(c.seed))
    if c.tracking then TrackingSynth.addFrames(events, SynthConfig(c.seed)) else events

  def times(feed: Vector[Envelope]): Vector[Long] =
    val kickoff = feed
      .collectFirst { case Envelope(_, _, _, _, _, _, _, Payload.Meta(m)) =>
        m.kickoffUtc.toInstant.toEpochMilli
      }
      .getOrElse(0L)
    feed.map(_.emittedAt.toInstant.toEpochMilli - kickoff)
