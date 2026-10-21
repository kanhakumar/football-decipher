package smi.raw

import cats.effect.IO
import fs2.Stream
import java.time.Instant
import scala.concurrent.duration.*
import smi.Rng
import smi.model.Timestamp

final case class EmulatorConfig(
    seed: Long,
    latencyMs: Long = 1_500,
    jitterMs: Double = 400,
    /** Share of events delivered late, by up to `maxDelayMs` extra. */
    outOfOrderRate: Double = 0.02,
    maxDelayMs: Long = 7_000,
    /** Events re-sent later with corrected coordinates. */
    corrections: Int = 3,
    /** Spurious duplicate events, sent and then deleted. */
    phantoms: Int = 2,
    /** One gap in delivery; held messages arrive in a burst when it ends. */
    dropoutMs: (Long, Long) = (20_000, 40_000)
)

/** A raw message and when it reaches the adapter, in ms since kickoff on the feed clock. */
final case class Delivery(atMs: Long, message: RawMessage)

/** What the emulator did to the feed, for reports and tests. */
final case class Mess(
    delayed: Int,
    corrections: Vector[Long],
    phantoms: Vector[Long],
    dropout: (Long, Long),
    reordered: Int
)

/** Turns a clean raw feed into a messy but recoverable delivery schedule (ADR-009). */
object FeedEmulator:
  def emulate(
      feed: Vector[RawMessage],
      kickoff: Instant,
      cfg: EmulatorConfig
  ): (Vector[Delivery], Mess) =
    val rng = Rng(cfg.seed)
    def sentMs(m: RawMessage) = m.lastModified.toInstant.toEpochMilli - kickoff.toEpochMilli
    val upserts = feed.filter(m => m.event.isDefined)
    val inPlay = upserts.filter(m => m.event.exists(e => e.x.isDefined && e.y.isDefined))

    // Base schedule: latency and jitter; a few events held back so they arrive out of order.
    var delayed = 0
    val base = feed.map { m =>
      val jitter = if m.event.isDefined then StrictMath.round(rng.gaussian() * cfg.jitterMs) else 0L
      val late =
        if m.event.isDefined && rng.chance(cfg.outOfOrderRate) then
          delayed += 1
          StrictMath.round(rng.between(2_000, cfg.maxDelayMs.toDouble))
        else 0L
      Delivery(sentMs(m) + cfg.latencyMs + math.max(-cfg.latencyMs + 100, jitter) + late, m)
    }

    // Corrections: an event re-sent a minute or more later with nudged coordinates.
    val corrected = pick(rng, inPlay, cfg.corrections).map { m =>
      val e = m.event.get
      def nudge(v: Double) =
        val moved = math.max(0.0, math.min(100.0, v + rng.between(-1.5, 1.5)))
        StrictMath.round(moved * 100) / 100.0
      val fixed = e.copy(x = e.x.map(nudge), y = e.y.map(nudge))
      val at = m.lastModified.toInstant.plusMillis(StrictMath.round(rng.between(60_000, 180_000)))
      m.copy(lastModified = Timestamp.of(at), event = Some(fixed))
    }

    // Phantoms: a duplicate with a fresh ID, then a delete for it shortly after.
    val maxId = upserts.flatMap(_.event.map(_.id)).maxOption.getOrElse(0L)
    val phantoms = pick(rng, inPlay, cfg.phantoms).zipWithIndex.flatMap { (m, i) =>
      val dup = m.copy(event = m.event.map(_.copy(id = maxId + 1 + i)))
      val gone =
        dup.lastModified.toInstant.plusMillis(StrictMath.round(rng.between(30_000, 90_000)))
      Vector(
        dup,
        RawMessage(
          RawKind.Event,
          RawAction.Delete,
          m.matchRef,
          Timestamp.of(gone),
          eventId = Some(maxId + 1 + i)
        )
      )
    }
    val extra = (corrected ++ phantoms).map(m => Delivery(sentMs(m) + cfg.latencyMs, m))

    // Dropout: nothing arrives for a while; held messages then arrive together, in order.
    val start = StrictMath.round(rng.between(10 * 60_000, 80 * 60_000))
    val length = StrictMath.round(rng.between(cfg.dropoutMs._1.toDouble, cfg.dropoutMs._2.toDouble))
    val end = start + length
    val all = (base ++ extra).zipWithIndex
      .sortBy((d, i) => (d.atMs, i))
      .map((d, _) => d)
    val burst = all.zipWithIndex.map { (d, i) =>
      if d.atMs >= start && d.atMs < end then d.copy(atMs = end + i % 50) else d
    }
    val deliveries = burst.zipWithIndex.sortBy((d, i) => (d.atMs, i)).map((d, _) => d)
    (
      deliveries,
      Mess(
        delayed,
        corrected.flatMap(_.eventRef),
        phantoms.flatMap(_.event.map(_.id)),
        (start, end),
        reordered(deliveries)
      )
    )

  /** The feed the emulator started from with its corrections applied in place and no phantoms: what
    * an adapter should end up with after reading the messy deliveries.
    */
  def truth(
      feed: Vector[RawMessage],
      deliveries: Vector[Delivery],
      mess: Mess
  ): Vector[RawMessage] =
    val fixes = deliveries
      .map(_.message)
      .filter(m => m.event.isDefined && m.eventRef.exists(mess.corrections.contains))
      .groupBy(_.eventRef)
      .view
      .mapValues(_.maxBy(_.lastModified.toInstant))
      .toMap
    feed.map(m => if m.event.isDefined then fixes.getOrElse(m.eventRef, m) else m)

  private def pick(rng: Rng, from: Vector[RawMessage], n: Int): Vector[RawMessage] =
    if from.isEmpty then Vector.empty
    else Vector.fill(n)(from((rng.uniform() * from.size).toInt)).distinctBy(_.eventRef)

  /** Event upserts delivered after an event with a later timestamp. */
  def reordered(deliveries: Vector[Delivery]): Int =
    deliveries
      .flatMap(_.message.event)
      .map(_.timestamp.toInstant.toEpochMilli)
      .foldLeft((Long.MinValue, 0)) { case ((max, n), ts) =>
        if ts < max then (max, n + 1) else (ts, n)
      }
      ._2

  /** Replays a schedule in real time, `speed` times faster (1 to 20). */
  def stream(deliveries: Vector[Delivery], speed: Double): Stream[IO, RawMessage] =
    require(speed >= 1 && speed <= 20, s"speed $speed must be between 1 and 20")
    val start = deliveries.headOption.map(_.atMs).getOrElse(0L)
    Stream.emits(deliveries).zipWithPrevious.evalMap { (prev, d) =>
      val gap = d.atMs - prev.map(_.atMs).getOrElse(start)
      IO.sleep(((gap / speed).toLong max 0L).millis).as(d.message)
    }
