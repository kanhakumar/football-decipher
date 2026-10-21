package smi

import munit.FunSuite
import smi.engine.*
import smi.generator.MatchSummary
import smi.model.*
import smi.tracking.{SynthConfig, TrackingSynth}

/** Workload from tracking: plausible numbers, exact on known input, honest when data is missing. */
class WorkloadSuite extends FunSuite:
  import EventKit.*
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private lazy val matches = Vector(154L, 7L).map { seed =>
    val feed = GeneratedMatches.generate(seed).envelopes
    (feed, Engine.replay(TrackingSynth.addFrames(feed, SynthConfig(seed))))
  }

  /** Starters who were neither substituted nor sent off. */
  private def fullMatch(feed: Vector[Envelope]): Vector[(PlayerId, Position)] =
    val evs = MatchSummary.events(feed)
    val gone = evs
      .flatMap(e =>
        e.details.collect {
          case s: SubstitutionDetails               => s.playerOffId
          case f: FoulDetails if f.card == Card.Red => e.playerId.get
        }
      )
      .toSet
    feed
      .collect { case Envelope(_, _, _, _, _, _, _, Payload.Team(l)) => l }
      .flatMap(_.starters)
      .filterNot(p => gone(p.playerId))
      .map(p => p.playerId -> p.position)

  test("full-match workloads are plausible") {
    matches.foreach { (feed, s) =>
      val players = fullMatch(feed)
      def v(id: String, p: PlayerId) = Engine.player(s, id, p).value.get
      val (keepers, outfield) = players.partition(_._2 == Position.GK)
      outfield.foreach { (p, _) =>
        val km = v("distance_covered", p) / 1000
        assert(km >= 9 && km <= 13, s"${p.value} $km km")
        assert(
          v("top_speed", p) >= 7.5 && v("top_speed", p) <= 9.8,
          s"${p.value} top ${v("top_speed", p)}"
        )
      }
      keepers.foreach((p, _) =>
        assert(
          v("distance_covered", p) / 1000 >= 3.5 && v("distance_covered", p) / 1000 <= 7,
          p.value
        )
      )
      val sprints = outfield.map((p, _) => v("sprints", p))
      val mean = sprints.sum / sprints.size
      println(
        f"outfield sprints per full match: mean $mean%.1f, range ${sprints.min}%.0f-${sprints.max}%.0f"
      )
      assert(mean >= 10 && mean <= 60, s"$mean")
      assertEquals(Engine.player(s, "distance_covered", outfield.head._1).basis, Basis.Tracking)
    }
  }

  test("fatigue shows: full-match players cover less ground per minute late than early") {
    matches.foreach { (feed, s) =>
      val outfield = fullMatch(feed).filter(_._2 != Position.GK).map(_._1)
      def perMinute(p: PlayerId, w: Window) =
        Engine.player(s, "distance_covered", p, w).value.get / (w.toMinute - w.fromMinute)
      val early = outfield.map(perMinute(_, Window(Some(1), 0, 15))).sum
      val late = outfield.map(perMinute(_, Window(Some(2), 75, 90))).sum
      println(
        f"distance per minute, first 15 vs 75-90: ${early / outfield.size}%.1f vs ${late / outfield.size}%.1f m"
      )
      assert(late < early, s"$late vs $early")
    }
  }

  private def frame(
      i: Int,
      x: Double,
      speed: Double,
      clockS: Double,
      players: List[TrackedPlayer] = Nil
  ) =
    val wall =
      java.time.Instant.parse("2026-10-18T15:00:00Z").plusMillis(StrictMath.round(clockS * 1000))
    val me = TrackedPlayer(player(home, 7), home, x, 30, Some(speed))
    val f = TrackingFrame(
      i,
      1,
      clockS,
      Timestamp.of(wall),
      StrictMath.round(clockS * 1000),
      5,
      Ball(x, 30),
      me :: players
    )
    envelope(Payload.Frame(f), PayloadType.TrackingFrame)

  /** A full minute of frames (300 at 5 Hz, so coverage is complete) with `move` setting x and
    * speed.
    */
  private def minute(move: Double => (Double, Double), skip: Double => Boolean = _ => false) =
    (0 until 300).map(i => i * 0.2).filterNot(skip).zipWithIndex.map { (t, i) =>
      val (x, v) = move(t)
      frame(i, x, v, t)
    }

  test("a known run gives exact distance, top speed and one sprint") {
    // Standing at x = 20, then 8 m/s for 2 s from t = 10, then standing at x = 36.
    val run =
      minute(t => if t < 10 then (20, 0) else if t <= 12 then (20 + (t - 10) * 8, 8) else (36, 0))
    val s = Engine.replay(header ++ run)
    assertEqualsDouble(Engine.player(s, "distance_covered", player(home, 7)).value.get, 16.0, 1e-6)
    assertEquals(Engine.player(s, "sprints", player(home, 7)).value, Some(1.0))
    assertEquals(Engine.player(s, "top_speed", player(home, 7)).value, Some(8.0))
    assertEqualsDouble(
      Engine.player(s, "high_speed_distance", player(home, 7)).value.get,
      16.0,
      1e-6
    )
    assertEquals(Engine.player(s, "distance_covered", player(home, 7)).basis, Basis.Tracking)
  }

  test("a slow run is not a sprint, and a gap in frames is not distance") {
    // Jogging at 4 m/s for 2 s, then a 2 s hole in the frames while the player jumps 10 m.
    val jog = minute(
      t =>
        if t < 10 then (20, 0)
        else if t <= 12 then (20 + (t - 10) * 4, 4)
        else if t < 30 then (28, 0)
        else (38, 0),
      skip = t => t > 28 && t < 30
    )
    val s = Engine.replay(header ++ jog)
    assertEquals(Engine.player(s, "sprints", player(home, 7)).value, Some(0.0))
    assertEqualsDouble(Engine.player(s, "distance_covered", player(home, 7)).value.get, 8.0, 1e-6)
  }

  test("a window with too few frames falls back or says not available") {
    val (feed, _) = matches.head
    val full = TrackingSynth.addFrames(feed, SynthConfig(154))
    val dropped = full.filterNot {
      case Envelope(_, _, _, _, _, _, _, Payload.Frame(f)) =>
        f.period == 1 && f.periodClockS >= 20 * 60 && f.periodClockS < 30 * 60
      case _ => false
    }
    val s = Engine.replay(dropped)
    val p = fullMatch(feed).find(_._2 != Position.GK).get._1
    val hole = Engine.player(s, "distance_covered", p, Window(Some(1), 20, 30))
    assertEquals(
      (hole.basis, hole.via, hole.capabilityLevel),
      (Basis.EventEstimate, Some("involvements"), 1)
    )
    assertEquals(Engine.player(s, "top_speed", p, Window(Some(1), 20, 30)).value, None)
    val fine = Engine.player(s, "distance_covered", p, Window(Some(1), 0, 10))
    assertEquals((fine.basis, fine.capabilityLevel), (Basis.Tracking, 1))
    assert(Engine.coverage(s, Window(Some(1), 0, 10)) >= 0.99)
  }

  test("the position provider says when it cannot answer") {
    val (feed, s) = matches.head
    val noTracking = Engine.replay(feed)
    val anyone = fullMatch(feed).head._1
    assertEquals(
      TrackingPositions(noTracking).position(anyone),
      Located.NotAvailable("no tracking data")
    )
    assert(TrackingPositions(s).position(anyone).isInstanceOf[Located.Available])
    val subbedOff = MatchSummary
      .events(feed)
      .flatMap(_.details.collect { case sd: SubstitutionDetails => sd.playerOffId })
      .head
    assertEquals(TrackingPositions(s).position(subbedOff), Located.NotAvailable("not on the pitch"))
    val lastFrame = s.workload.lastFrameMs.get
    val later = event(999_999, EventType.Pass, home, Some(2), Some((40, 30)), 0).copy(
      wallclockUtc = Timestamp.of(java.time.Instant.ofEpochMilli(lastFrame + 5_000))
    )
    assertEquals(
      TrackingPositions(Engine.apply(s, create(later))).position(anyone),
      Located.NotAvailable("tracking is stale")
    )
  }
