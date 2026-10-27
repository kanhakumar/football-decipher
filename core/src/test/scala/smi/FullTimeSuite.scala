package smi

import munit.FunSuite
import smi.insight.{MomentState, Moments, Rederive}
import smi.model.*

/** The full-time recap (ADR-023): computed from the engine's own events, triggered by the second
  * half's period_end or the match status turning full_time, whichever comes first, and only once.
  */
class FullTimeSuite extends FunSuite:
  import EventKit.*

  private def second(e: Event): Event =
    e.copy(period = 2, matchClockS = 2700 + e.periodClockS)

  private val homeGoal = shot(1, home, 9, (99, 34), 600, Outcome.Goal)
  private val awayGoal = second(shot(2, away, 9, (99, 34), 1000, Outcome.Goal))
  private val awaySaved = second(shot(3, away, 10, (99, 34), 1500, Outcome.Saved))
  private val homeRed = second(foul(4, home, 5, (40, 34), 1800, Card.Red, player(away, 7)))
  private def periodEnd(id: Long, period: Int, clockS: Double) =
    event(id, EventType.PeriodEnd, home, None, None, clockS, period = period)
  private val halfTime = periodEnd(5, 1, 2760)
  private val fullTime = periodEnd(6, 2, 2940)
  private val finished =
    envelope(Payload.Meta(meta.copy(status = MatchStatus.FullTime)), PayloadType.MatchMeta)

  private val play = Vector(homeGoal, halfTime, awayGoal, awaySaved, homeRed).map(create)

  private def recaps(s: MomentState) = s.out.filter(_.kind == InsightKind.FullTime)
  private def recap(s: MomentState) = s.ranked.filter(_.kind == InsightKind.FullTime) match
    case Vector(one) => one
    case other       => fail(s"expected one active recap, got ${other.size}")
  private def n(i: Insight, k: String) = i.facts(k).flatMap(_.asNumber).flatMap(_.toInt)
  private def rederives(s: MomentState, i: Insight) =
    assertEquals(Rederive.check(i, id => s.engine.events.get(id).map(_.event)), Right(()))

  test("the second half's period_end gives one recap, built from the match's events") {
    val s = Moments.run(header ++ play :+ create(fullTime))
    val r = recap(s)
    // 1-1, and the home side finished with ten: their point is the story.
    assertEquals(r.teams, List(home, away))
    assertEquals(r.whyItMatters.code.value, "ten_men_take_a_point")
    assertEquals(
      List("team_goals", "opponent_goals", "team_shots", "opponent_shots").map(n(r, _)),
      List(Some(1), Some(1), Some(1), Some(2))
    )
    assertEquals(n(r, "opponent_shots_on_target"), Some(2))
    assertEquals(n(r, "ctx_players_for"), Some(10))
    assertEquals(r.evidenceEventIds.last, fullTime.eventId, "the whistle is the last evidence")
    assertEquals(r.videoTsMs, fullTime.videoTsMs)
    rederives(s, r)
  }

  test("the first half's period_end is not full time") {
    assertEquals(recaps(Moments.run(header ++ play)), Vector.empty)
  }

  test("a feed that never sends the final whistle gets its recap when the status turns full_time") {
    val s = Moments.run(header ++ play :+ finished)
    val r = recap(s)
    assertEquals(r.evidenceEventIds.last, homeRed.eventId, "anchored on the last event")
    rederives(s, r)
  }

  test("whichever signal comes first wins, and the other adds nothing") {
    for feed <- List(play :+ create(fullTime) :+ finished, play :+ finished :+ create(fullTime))
    do assertEquals(recaps(Moments.run(header ++ feed)).size, 1)
  }

  test("a win against the run of play goes to the winner, with the xG it beat") {
    val longShot = shot(11, home, 8, (80, 34), 300, Outcome.Goal)
    val chances =
      (12 to 14).map(i => second(shot(i, away, 9, (99, 34), 600.0 + i, Outcome.Saved))).toVector
    val s = Moments.run(header ++ (longShot +: chances :+ fullTime).map(create))
    val r = recap(s)
    assertEquals(r.teams.head, home)
    assertEquals(r.whyItMatters.code.value, "win_against_the_run_of_play")
    assert(r.whyItMatters.params("opponent_xg").isDefined)
    rederives(s, r)
  }

  test("a correction after the whistle withdraws the recap and replaces it") {
    val base = Moments.run(header ++ play :+ create(fullTime))
    val s = Moments.run(header ++ play :+ create(fullTime) :+ delete(awayGoal.eventId))
    val old = recap(base)
    val now = recap(s)
    assertEquals(now.supersedes, Some(old.insightId))
    assert(
      s.out.exists(i => i.insightId == old.insightId && i.status.contains(InsightStatus.Retracted))
    )
    // Without the away goal the home side won 1-0, ten men or not.
    assertEquals((n(now, "team_goals"), n(now, "opponent_goals")), (Some(1), Some(0)))
    assertEquals(now.whyItMatters.code.value, "full_time_win")
    rederives(s, now)
  }
