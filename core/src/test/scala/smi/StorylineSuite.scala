package smi

import munit.FunSuite
import smi.generator.Squads
import smi.insight.MomentState
import smi.model.*

/** The scripted story must be found by the detectors: counter goal, red card, high press. */
class StorylineSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private val home = Squads.home.teamId
  private val away = Squads.away.teamId

  private def minuteFact(i: Insight) = i.facts("minute").flatMap(_.asNumber).flatMap(_.toInt)
  private def code(i: Insight) = i.whyItMatters.code.value

  private def counterGoals(s: MomentState): Vector[(String, Int)] =
    s.ranked
      .filter(i => code(i) == "counter_attack_goal")
      .map(i => (i.teams.head.value, minuteFact(i).get))

  test("every goal the generator labels a counter is detected as one, and only those") {
    InsightRuns.default.foreach { (m, s) =>
      val labelled = m.summary.goals
        .filter(_.situation.contains(ShotSituation.Counter))
        .map(g => (g.team.value, g.minute.toInt + 1))
      assertEquals(counterGoals(s).sorted, labelled.sorted, s"seed ${m.seed}")
    }
  }

  test("every red card has a red_card insight at its minute") {
    InsightRuns.default.foreach { (m, s) =>
      val reds = m.summary.redCards.map(r => (r.team.value, r.minute.toInt + 1))
      val found = s.ranked
        .filter(_.kind == InsightKind.RedCard)
        .map(i => (i.teams.head.value, minuteFact(i).get))
      assertEquals(found.sorted, reds.sorted, s"seed ${m.seed}")
    }
  }

  test("the home high press (55-70) is detected as a pressure shift in at least 80% of seeds") {
    val hits = InsightRuns.default.count { (_, s) =>
      s.ranked.exists { i =>
        val end = i.facts("after_to_minute").flatMap(_.asNumber).map(_.toDouble).getOrElse(0.0)
        i.kind == InsightKind.PressureShift && i.teams.head == home && end >= 57 && end <= 72
      }
    }
    println(s"home press detected in $hits/50 seeds")
    assert(hits >= 40, s"$hits/50")
  }

  test("without the scripted beats, the counter goal and red card stories are rare") {
    def near(s: MomentState, k: String, team: TeamId, target: Int) =
      s.ranked.exists(i =>
        code(i) == k && i.teams.head == team && minuteFact(i).exists(t => math.abs(t - target) <= 5)
      )
    val counters = InsightRuns.noBeats.count((_, s) => near(s, "counter_attack_goal", away, 34))
    val reds = InsightRuns.noBeats.count((_, s) => near(s, "down_to_ten", home, 78))
    println(s"control: counter goal story $counters/50, red card story $reds/50")
    assert(counters <= 10 && reds <= 10)
  }
