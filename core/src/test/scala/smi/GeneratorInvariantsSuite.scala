package smi

import munit.FunSuite
import scala.collection.mutable
import smi.GeneratedMatches.Match
import smi.model.*

/** Invariants from CLAUDE.md, checked on 50 seeds of the default scenario. */
class GeneratorInvariantsSuite extends FunSuite:
  private val matches = GeneratedMatches.default

  private def eachMatch(name: String)(check: Match => Unit): Unit =
    test(s"$name (50 seeds)") {
      matches.foreach(m =>
        try check(m)
        catch case e: munit.FailExceptionLike[?] => fail(s"seed ${m.seed}: ${e.getMessage}")
      )
    }

  private def nonDecreasing(xs: Vector[Double]) = xs.zip(xs.drop(1)).forall(_ <= _)

  /** Event location in the home team's frame, so both teams' events can be compared. */
  private def physical(m: Match, e: Event): Option[(Double, Double)] =
    val home = m.lineups.head.teamId
    for x <- e.x; y <- e.y yield if e.teamId == home then (x, y) else (105 - x, 68 - y)

  private def samePlace(a: Option[(Double, Double)], b: Option[(Double, Double)]): Boolean =
    (a, b) match
      case (Some((ax, ay)), Some((bx, by))) => math.abs(ax - bx) < 0.06 && math.abs(ay - by) < 0.06
      case _                                => false

  eachMatch("seq is 0..n-1 and emitted_at is ordered") { m =>
    assertEquals(m.envelopes.map(_.seq), m.envelopes.indices.map(_.toLong).toVector)
    val emitted = m.envelopes.map(_.emittedAt.toInstant)
    assertEquals(emitted.sorted, emitted)
  }

  eachMatch("clocks are monotonic and each period is bracketed by start and end") { m =>
    val evs = m.events
    assert(nonDecreasing(evs.map(_.matchClockS)), "match_clock_s")
    assert(nonDecreasing(evs.map(_.videoTsMs.toDouble)), "video_ts_ms")
    assert(nonDecreasing(evs.map(_.wallclockUtc.toInstant.toEpochMilli.toDouble)), "wallclock_utc")
    assertEquals(evs.map(_.period).distinct, Vector(1, 2))
    evs.groupBy(_.period).toVector.sortBy(_._1).foreach { (p, inPeriod) =>
      assert(nonDecreasing(inPeriod.map(_.periodClockS)), s"period $p clock")
      assertEquals(inPeriod.head.`type`, EventType.PeriodStart, s"period $p first event")
      assertEquals(inPeriod.last.`type`, EventType.PeriodEnd, s"period $p last event")
      assert(inPeriod.last.periodClockS >= 45 * 60, s"period $p lasts 45 minutes")
    }
  }

  eachMatch("the final score equals the goal events") { m =>
    val finalMeta = m.envelopes.collect { case Envelope(_, _, _, _, _, _, _, Payload.Meta(x)) =>
      x
    }.last
    assertEquals(finalMeta.status, MatchStatus.FullTime)
    val goals = m.events
      .filter(_.outcome.contains(Outcome.Goal))
      .groupMapReduce(_.teamId.value)(_ => 1)(_ + _)
    assertEquals(finalMeta.score.toMap.filter(_._2 > 0), goals)
  }

  eachMatch("11 per side minus red cards; only players on the pitch act") { m =>
    val onPitch = m.lineups.map(l => l.teamId -> mutable.Set.from(l.starters.map(_.playerId))).toMap
    val bench = m.lineups.map(l => l.teamId -> mutable.Set.from(l.bench.map(_.playerId))).toMap
    val reds = mutable.Map.empty[TeamId, Int].withDefaultValue(0)
    val subs = mutable.Map.empty[TeamId, Int].withDefaultValue(0)
    m.events.foreach { e =>
      e.playerId.foreach(p =>
        assert(onPitch(e.teamId)(p), s"${e.eventId.value}: ${p.value} is not on the pitch")
      )
      e.details match
        case Some(s: SubstitutionDetails) =>
          assert(
            bench(e.teamId).remove(s.playerOnId),
            s"${s.playerOnId.value} was not on the bench"
          )
          onPitch(e.teamId) -= s.playerOffId
          onPitch(e.teamId) += s.playerOnId
          subs(e.teamId) += 1
        case Some(f: FoulDetails) if f.card == Card.Red =>
          onPitch(e.teamId) -= e.playerId.get
          reds(e.teamId) += 1
        case _ => ()
      onPitch.foreach((t, ps) =>
        assertEquals(ps.size, 11 - reds(t), s"${e.eventId.value} ${t.value}")
      )
    }
    subs.foreach((t, n) => assert(n <= 5, s"${t.value} made $n substitutions"))
  }

  eachMatch("a completed pass ends where its receiver acts next") { m =>
    val evs = m.events
    evs.indices.foreach { i =>
      (evs(i).details, evs(i).outcome) match
        case (Some(p: PassDetails), Some(Outcome.Complete)) =>
          val rest = evs.drop(i + 1).takeWhile(_.possessionId == evs(i).possessionId)
          val (before, next) = rest.span(e => e.teamId != evs(i).teamId || e.x.isEmpty)
          if !before.exists(_.`type` == EventType.Foul) then
            next.headOption.foreach { n =>
              assertEquals(
                (n.x, n.y),
                (Some(p.endX), Some(p.endY)),
                s"${evs(i).eventId.value} -> ${n.eventId.value}"
              )
              assertEquals(n.playerId, p.recipientId, s"receiver of ${evs(i).eventId.value}")
            }
        case _ => ()
    }
  }

  eachMatch("pressure, tackles and interceptions happen where the ball is") { m =>
    val located = m.events.filter(_.x.isDefined)
    located.zip(located.drop(1)).foreach { (a, b) =>
      val defensive = Set(EventType.Pressure, EventType.Tackle, EventType.Interception)
      if defensive(a.`type`) && a.period == b.period then
        assert(
          samePlace(physical(m, a), physical(m, b)),
          s"${a.eventId.value} -> ${b.eventId.value}"
        )
    }
  }

  eachMatch("every possession change hands the ball to the other team") { m =>
    val onBall = Set(EventType.Pass, EventType.Shot, EventType.Dribble, EventType.SetPiece)
    val evs = m.events
    evs.indices.foreach { i =>
      evs(i).details.foreach {
        case pc: PossessionChangeDetails =>
          assertNotEquals(pc.fromTeam, pc.toTeam)
          assertEquals(evs(i).teamId, pc.toTeam)
          val untilNextChange = evs
            .drop(i + 1)
            .takeWhile(e =>
              e.`type` != EventType.PossessionChange && e.`type` != EventType.PeriodEnd
            )
          untilNextChange.find(e => onBall(e.`type`)).foreach { n =>
            assertEquals(n.teamId, pc.toTeam, s"${evs(i).eventId.value} -> ${n.eventId.value}")
          }
        case _ => ()
      }
    }
  }
