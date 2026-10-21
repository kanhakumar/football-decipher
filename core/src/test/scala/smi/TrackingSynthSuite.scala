package smi

import io.circe.syntax.*
import munit.FunSuite
import smi.generator.MatchSummary
import smi.model.*
import smi.tracking.{SynthConfig, TrackingSynth}

/** The synthesized tracking obeys simple physics and agrees with the event feed. */
class TrackingSynthSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private lazy val feed = GeneratedMatches.generate(154).envelopes
  private lazy val frames = TrackingSynth.synthesize(feed, SynthConfig(154))
  private lazy val tracked: Vector[TrackingFrame] = frames.collect {
    case Envelope(_, _, _, _, _, _, _, Payload.Frame(f)) => f
  }
  private lazy val events = MatchSummary.events(feed)
  private lazy val lineups = feed.collect { case Envelope(_, _, _, _, _, _, _, Payload.Team(l)) =>
    l
  }
  private lazy val home = lineups.head.teamId
  private lazy val positions =
    lineups.flatMap(l => (l.starters ++ l.bench).map(p => p.playerId -> p.position)).toMap

  private def ms(t: Timestamp) = t.toInstant.toEpochMilli
  private def homeFrame(e: Event): (Double, Double) =
    if e.teamId == home then (e.x.get, e.y.get) else (105 - e.x.get, 68 - e.y.get)
  private def cap(p: PlayerId): Double = positions(p) match
    case Position.GK                                                           => 7.0
    case Position.CB | Position.RB | Position.LB | Position.RWB | Position.LWB => 8.5
    case Position.CDM | Position.CM | Position.CAM | Position.RM | Position.LM => 9.0
    case _                                                                     => 9.5

  test("the same seed gives identical frames") {
    assertEquals(TrackingSynth.synthesize(feed, SynthConfig(154)), frames)
  }

  test("frames are 5 Hz, within the schema, and cover both periods") {
    assertEquals(tracked.map(_.period).distinct, Vector(1, 2))
    tracked.zip(tracked.drop(1)).filter((a, b) => a.period == b.period).foreach { (a, b) =>
      assertEquals(ms(b.wallclockUtc) - ms(a.wallclockUtc), 200L)
    }
    val schema = SchemaFiles.schema("canonical.schema.json")
    frames.zipWithIndex.collect { case (f, i) if i % 100 == 0 => f }.foreach { f =>
      assertEquals(SchemaFiles.errors(schema, f.asJson.noSpaces), Nil, s"frame ${f.seq}")
    }
  }

  test("exactly the players on the pitch appear, with substitutions and red cards") {
    val changes = events.filter(e =>
      e.`type` == EventType.Substitution || e.details.exists {
        case f: FoulDetails => f.card == Card.Red
        case _              => false
      }
    )
    var onPitch = lineups.flatMap(_.starters.map(_.playerId)).toSet
    var pending = changes
    tracked.foreach { f =>
      while pending.nonEmpty && ms(pending.head.wallclockUtc) <= ms(f.wallclockUtc) do
        pending.head.details match
          case Some(sub: SubstitutionDetails) =>
            onPitch = onPitch - sub.playerOffId + sub.playerOnId
          case _ => onPitch = onPitch -- pending.head.playerId
        pending = pending.tail
      assertEquals(f.players.map(_.playerId).toSet, onPitch, s"frame ${f.frame}")
    }
  }

  test("no player exceeds their top speed or the acceleration limit") {
    tracked.foreach(f =>
      f.players.foreach(p =>
        assert(p.speedMs.get <= cap(p.playerId) * 1.05, s"${p.playerId.value} ${p.speedMs}")
      )
    )
    tracked.zip(tracked.drop(1)).filter((a, b) => a.period == b.period).foreach { (a, b) =>
      val before = a.players.map(p => p.playerId -> p.speedMs.get).toMap
      b.players.foreach { p =>
        before
          .get(p.playerId)
          .foreach(v =>
            assert(
              math.abs(p.speedMs.get - v) / 0.2 <= TrackingSynth.MaxAcceleration + 0.2,
              s"frame ${b.frame} ${p.playerId.value}"
            )
          )
      }
    }
  }

  test("the ball is at the event spot when an event happens on a frame") {
    val byTime = tracked.map(f => ms(f.wallclockUtc) -> f).toMap
    val onFrame = events.filter(e => e.x.isDefined && byTime.contains(ms(e.wallclockUtc)))
    assert(onFrame.size > 300, onFrame.size.toString)
    onFrame.foreach { e =>
      val f = byTime(ms(e.wallclockUtc))
      val (x, y) = homeFrame(e)
      // Several events can share a timestamp; the ball sits at the last of them.
      val sameTime = events.filter(o => o.x.isDefined && ms(o.wallclockUtc) == ms(e.wallclockUtc))
      if sameTime.size == 1 then
        assert(
          math.abs(f.ball.x - x) < 0.02 && math.abs(f.ball.y - y) < 0.02,
          s"${e.eventId.value} ball ${f.ball} vs ($x, $y)"
        )
    }
  }

  test("the acting player is near the event spot at event time for at least 90% of events") {
    val byTime = tracked.map(f => ms(f.wallclockUtc) -> f).toMap
    val checked = events.filter(e => e.x.isDefined && e.playerId.isDefined).flatMap { e =>
      val t = ms(e.wallclockUtc)
      val f = byTime.get(t).orElse(byTime.get(t - 100)).orElse(byTime.get(t + 100))
      f.flatMap(_.players.find(p => e.playerId.contains(p.playerId))).map { p =>
        val (x, y) = homeFrame(e)
        StrictMath.hypot(p.x - x, p.y - y)
      }
    }
    val near = checked.count(_ <= 3.0).toDouble / checked.size
    println(f"actors within 3 m of the event spot: ${near * 100}%.1f%% of ${checked.size} events")
    assert(near >= 0.9, s"$near")
  }
