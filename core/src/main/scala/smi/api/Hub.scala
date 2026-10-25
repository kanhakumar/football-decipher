package smi.api

import cats.effect.{Fiber, IO, Ref, Resource}
import cats.syntax.all.*
import cats.effect.std.{Mutex, UUIDGen}
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

  /** Up to 10 video minutes: fast replays need it so narration has real time (D5). */
  val Delays: (Double, Double) = (0.0, 600.0)

  def validate(c: MatchConfig): Either[String, MatchConfig] =
    if c.speed < Speeds._1 || c.speed > Speeds._2 then
      Left(s"speed ${c.speed} must be ${Speeds._1} to ${Speeds._2}")
    else if c.delayS < Delays._1 || c.delayS > Delays._2 then
      Left(s"delay_s ${c.delayS} must be ${Delays._1} to ${Delays._2}")
    else Right(c)

/** One match. `live` is the generator's position (engine and detectors run here); `viewer` trails
  * it by the viewer delay (ADR-013, D5). With a `Replay` source the whole feed is known up front;
  * with a `Bus` source it grows as envelopes arrive, and `ended` says the last one has (ADR-016).
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
    closed: Boolean = false,
    source: FeedSource = FeedSource.Replay,
    runId: String = "",
    /** Whether the feed is complete (always for a replay; after the last bus message otherwise). */
    ended: Boolean = true,
    kickoffMs: Option[Long] = None,
    /** Video time of the newest envelope received. */
    latestMs: Double = 0
):
  def matchId: Option[MatchId] = feed.headOption.map(_.matchId)

  /** Advances the playhead by `realMs` of wall time at the configured speed. */
  def tick(realMs: Double): Playback =
    if status != Status.Running then this
    else
      val delay = config.delayS * 1000
      val advanced = clockMs + realMs * config.speed
      val clock = source match
        case FeedSource.Replay => advanced
        // The viewer never passes what has arrived; during gaps in the feed the clock still runs.
        case FeedSource.Bus => math.min(advanced, latestMs + delay)
      var v = viewer
      while v < feed.size && times(v) <= clock - delay do v += 1
      val done = ended && v == feed.size
      // On a bus the live side processes every envelope as soon as it has arrived.
      val liveUpTo = if source == FeedSource.Replay then clock else Double.PositiveInfinity
      feedLive(liveUpTo).closeDetectors
        .copy(clockMs = clock, viewer = v, status = if done then Status.Finished else status)

  /** Appends an envelope that arrived on the bus. */
  def receive(env: Envelope, last: Boolean): Playback =
    val kickoff = kickoffMs.orElse(env.payload match
      case Payload.Meta(m) => Some(m.kickoffUtc.toInstant.toEpochMilli)
      case _               => None
    )
    val t = env.emittedAt.toInstant.toEpochMilli - kickoff.getOrElse(0L)
    copy(
      feed = feed :+ env,
      times = times :+ t,
      kickoffMs = kickoff,
      latestMs = math.max(latestMs, t.toDouble),
      ended = ended || last,
      clockMs = if feed.isEmpty then t.toDouble else clockMs
    )

  private def feedLive(clock: Double): Playback =
    var p = this
    var i = live
    while i < feed.size && times(i) <= clock do
      p = p.process(Moments.step(p.moments, feed(i)))
      i += 1
    p.copy(live = i)

  /** Closes the detectors once the live cursor has passed the last envelope. */
  private def closeDetectors: Playback =
    if ended && live == feed.size && feed.nonEmpty && !closed then
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
      case (InsightKind.TacticalShift, false, None) =>
        Overlays
          .shape(i, recorded.overlays.size + 1, moments.engine)
          .fold(recorded)(o => recorded.publish(o, Some(i.insightId)))
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
final class Hub(
    val state: SignallingRef[IO, Playback],
    lock: Mutex[IO],
    log: Log,
    bus: Option[MatchBus],
    store: MatchStore,
    publishing: Ref[IO, Option[Fiber[IO, Throwable, Unit]]]
):
  def snapshot: IO[Playback] = state.get
  def source: FeedSource = if bus.isDefined then FeedSource.Bus else FeedSource.Replay

  def start(c: MatchConfig): IO[Either[String, Playback]] =
    MatchConfig.validate(c) match
      case Left(err) => IO.pure(Left(err))
      case Right(cfg) =>
        lock.lock.surround {
          for
            feed <- IO.blocking(Hub.feed(cfg))
            times = Hub.times(feed)
            prev <- state.get
            runId <- UUIDGen[IO].randomUUID.map(_.toString)
            _ <- stopPublisher
            next = bus match
              case None =>
                Playback(
                  prev.generation + 1,
                  cfg,
                  Status.Running,
                  feed,
                  times,
                  clockMs = times.headOption.getOrElse(0L).toDouble,
                  runId = runId
                )
              case Some(_) =>
                Playback(
                  prev.generation + 1,
                  cfg,
                  Status.Running,
                  source = FeedSource.Bus,
                  runId = runId,
                  ended = false
                )
            _ <- state.set(next)
            _ <- bus.traverse_(startPublisher(_, runId, feed))
            _ <- log.info(
              s"match ${next.generation} started: seed ${cfg.seed}, speed ${cfg.speed}x, " +
                s"delay ${cfg.delayS} s, tracking ${cfg.tracking}, ${feed.size} envelopes" +
                bus.fold("")(b => s" via the ${b.name} bus (run $runId)")
            )
            _ <- IO.whenA(cfg.delayS / cfg.speed < Hub.MinNarrationS)(
              log.info(
                f"warning: delay ${cfg.delayS} s at ${cfg.speed}x leaves narration " +
                  f"${cfg.delayS / cfg.speed}%.1f s of real time; model lines will arrive late"
              )
            )
          yield Right(next)
        }

  private def stopPublisher: IO[Unit] =
    publishing.getAndSet(None).flatMap(_.traverse_(_.cancel))

  /** The provider side of a bus match: publishes the generated feed at playback pace. */
  private def startPublisher(b: MatchBus, runId: String, feed: Vector[Envelope]): IO[Unit] =
    val control = state.get.map(p =>
      Publisher.Control(p.config.speed, p.runId == runId, p.status == Status.Paused)
    )
    Publisher
      .run(b, runId, feed, control)
      .handleErrorWith(e => log.error(s"publishing run $runId failed", e))
      .start
      .flatMap(f => publishing.set(Some(f)))

  /** Takes one envelope from the bus: for the current run, or the start of a run published
    * elsewhere (the generator job). Envelopes of a replaced run are dropped.
    */
  def onBus(m: BusMessage): IO[Unit] =
    lock.lock
      .surround(state.modify { p =>
        if p.source == FeedSource.Bus && p.runId == m.runId then
          (p.receive(m.envelope, m.last), None)
        else
          m.envelope.payload match
            case Payload.Meta(meta) if p.runId != m.runId =>
              val speed = m.speed.max(MatchConfig.Speeds._1).min(MatchConfig.Speeds._2)
              val cfg = MatchConfig(seed = meta.seed.getOrElse(0L), speed = speed)
              val next = Playback(
                p.generation + 1,
                cfg,
                Status.Running,
                source = FeedSource.Bus,
                runId = m.runId,
                ended = false
              ).receive(m.envelope, m.last)
              (
                next,
                Some(s"match ${next.generation} started by another publisher (run ${m.runId})")
              )
            case _ => (p, None)
      })
      .flatMap(_.traverse_(log.info))

  /** Moves `from` to `to`; logs only when the status actually changes. */
  private def toggle(from: Status, to: Status): IO[Playback] =
    state
      .modify { p =>
        val next = if p.status == from then p.copy(status = to) else p
        (next, (next, p.status == from))
      }
      .flatMap((p, changed) =>
        log
          .info(s"${Status.wire(to)} at match clock ${Log.clock(p.clockMs / 1000)}")
          .whenA(changed)
          .as(p)
      )

  def pause: IO[Playback] = toggle(Status.Running, Status.Paused)
  def resume: IO[Playback] = toggle(Status.Paused, Status.Running)

  def speed(s: Double): IO[Either[String, Playback]] =
    state.get.flatMap { p =>
      MatchConfig.validate(p.config.copy(speed = s)) match
        case Left(err) => IO.pure(Left(err))
        case Right(c) =>
          state
            .updateAndGet(_.copy(config = c))
            .flatTap(_ => log.info(s"speed ${c.speed}x"))
            .map(Right(_))
    }

  /** Accepts an overlay from the agent service (it must decode strictly, which validates it). */
  def addOverlay(o: Overlay): IO[Either[String, Overlay]] =
    state
      .modify { p =>
        if p.matchId.forall(_ != o.matchId) then
          (p, Left(s"overlay is for ${o.matchId.value}, not the current match"))
        else if p.overlays.exists(x => x.overlayId == o.overlayId && x.op == o.op) then
          (p, Left(s"${o.overlayId.value} already published"))
        else (p.publish(o, None), Right(o))
      }
      .flatTap {
        case Right(o) =>
          state.get.flatMap(p => keep(store.saveOverlay(p.runId, o))) >> log.info(
            s"overlay ${o.overlayId.value} ${OverlayOp.wire(o.op)} accepted from the agent service"
          )
        case Left(err) => log.info(s"overlay refused: $err")
      }

  /** Runs the playhead: every tick advances by the real time that passed. */
  def ticker(every: FiniteDuration = 20.millis): Stream[IO, Unit] =
    Stream.eval(IO.monotonic).flatMap { start =>
      Stream
        .fixedRate[IO](every)
        .evalMapAccumulate(start) { (last, _) =>
          IO.monotonic.flatMap { now =>
            lock.lock
              .surround(state.modify { p =>
                val next = p.tick((now - last).toNanos / 1e6)
                (next, (Log.changes(p, next), p, next))
              })
              .flatMap((lines, before, after) =>
                lines.traverse_(log.info) >> persist(before, after)
              )
              .handleErrorWith(e => log.error("playback tick failed", e))
              .as((now, ()))
          }
        }
        .as(())
    }

  /** Hands new insights and overlays to the store, off the ticker's path. */
  private def persist(before: Playback, after: Playback): IO[Unit] =
    if store eq MatchStore.none then IO.unit
    else
      val same = before.generation == after.generation
      val insights = if same then after.insights.drop(before.insights.size) else after.insights
      val overlays = if same then after.overlays.drop(before.overlays.size) else after.overlays
      keep(
        insights.traverse_(store.saveInsight(after.runId, _)) >>
          overlays.traverse_(store.saveOverlay(after.runId, _))
      ).whenA(insights.nonEmpty || overlays.nonEmpty)

  private def keep(write: IO[Unit]): IO[Unit] =
    write.handleErrorWith(e => log.error(s"saving to the ${store.name} store failed", e)).start.void

object Hub:
  /** Real seconds of viewer delay below which model narration usually misses its moment. */
  val MinNarrationS = 8.0

  def resource(
      log: Log = Log.silent,
      bus: Option[MatchBus] = None,
      store: MatchStore = MatchStore.none
  ): Resource[IO, Hub] =
    for
      ref <- Resource.eval(SignallingRef[IO].of(Playback()))
      lock <- Resource.eval(Mutex[IO])
      publishing <- Resource.eval(Ref[IO].of(Option.empty[Fiber[IO, Throwable, Unit]]))
      hub = Hub(ref, lock, log, bus, store, publishing)
      _ <- hub.ticker().compile.drain.background
      _ <- bus.traverse_(b =>
        b.subscribe.flatMap(messages =>
          messages
            .evalMap(m => hub.onBus(m).handleErrorWith(e => log.error("bus message failed", e)))
            .compile
            .drain
            .background
        )
      )
      _ <- Resource.onFinalize(hub.stopPublisher)
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
