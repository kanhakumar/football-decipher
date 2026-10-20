package smi

import munit.FunSuite
import smi.insight.Moments
import smi.model.*

/** Corrections to evidence retract or replace insights; unrelated corrections change nothing. */
class CorrectionSuite extends FunSuite:
  import EventKit.*

  private val goal = shot(1, home, 9, (99, 34), 100, Outcome.Goal)
  private val chance = shot(2, away, 9, (99, 34), 200, Outcome.Saved)
  private val other = pass(3, home, 6, (40, 34), (50, 34), 300, complete = true)
  private val feed = header ++ Vector(goal, chance, other).map(create)
  private val base = Moments.run(feed)
  private def kinds(s: smi.insight.MomentState) = s.ranked.map(_.kind).toSet

  test("the clean feed has a goal and a key chance") {
    assertEquals(kinds(base), Set(InsightKind.Goal, InsightKind.KeyChance))
  }

  test("deleting a goal retracts its insight") {
    val s = Moments.run(feed :+ delete(goal.eventId))
    val original = base.ranked.find(_.kind == InsightKind.Goal).get
    assert(!kinds(s).contains(InsightKind.Goal))
    assertEquals(s.out.last.insightId, original.insightId)
    assertEquals(s.out.last.status, Some(InsightStatus.Retracted))
  }

  test("changing an evidence event's outcome retracts and supersedes with new facts") {
    val s = Moments.run(feed :+ update(chance.copy(outcome = Some(Outcome.OffTarget))))
    val old = base.ranked.find(_.kind == InsightKind.KeyChance).get
    val replacement = s.ranked.find(_.kind == InsightKind.KeyChance).get
    assertEquals(replacement.supersedes, Some(old.insightId))
    assertEquals(replacement.whyItMatters.code.value, "big_chance_missed")
    assert(
      s.out.exists(i => i.insightId == old.insightId && i.status.contains(InsightStatus.Retracted))
    )
  }

  test("a saved shot corrected to a goal becomes a goal and stops being a missed chance") {
    val s = Moments.run(feed :+ update(chance.copy(outcome = Some(Outcome.Goal))))
    assertEquals(
      s.ranked.filter(_.kind == InsightKind.Goal).map(_.whyItMatters.code.value).sorted,
      Vector("equaliser", "takes_lead")
    )
    assert(!kinds(s).contains(InsightKind.KeyChance))
  }

  test("a correction to an unrelated event changes nothing") {
    val s = Moments.run(feed :+ update(other.copy(x = Some(41))))
    assertEquals(s.out, base.out)
  }
