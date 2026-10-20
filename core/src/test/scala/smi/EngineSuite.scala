package smi

import munit.FunSuite
import smi.engine.*
import smi.model.*

/** Known sequences give known stats; corrections and deletes update them. */
class EngineSuite extends FunSuite:
  import EventKit.*

  private val p1 = pass(1, home, 2, (10, 34), (20, 34), 10, complete = true)
  private val p2 = pass(2, home, 3, (20, 34), (40, 34), 15, complete = true)
  private val p3 = pass(3, home, 4, (40, 34), (80, 34), 20, complete = false)
  private val goal = shot(4, home, 9, (94, 34), 70, Outcome.Goal)
  private val booking = foul(5, away, 4, (50, 30), 130, Card.Yellow, player(home, 9))
  private val feed = header ++ Vector(p1, p2, p3, goal, booking).map(create)
  private val s = Engine.replay(feed)

  private def team(id: String, t: TeamId = home, w: Window = Window.Match) =
    Engine.team(s, id, t, w).value

  test("a known sequence gives known totals") {
    assertEquals(team("passes"), Some(3.0))
    assertEquals(team("passes_completed"), Some(2.0))
    assertEqualsDouble(team("pass_completion").get, 200.0 / 3, 1e-9)
    assertEquals(team("shots"), Some(1.0))
    assertEquals(team("goals"), Some(1.0))
    assertEqualsDouble(
      team("xg").get,
      Enrich.xg(94, 34, Some(BodyPart.RightFoot), ShotSituation.OpenPlay),
      1e-12
    )
    assertEquals(team("fouls", away), Some(1.0))
    assertEquals(team("yellow_cards", away), Some(1.0))
    assertEquals(team("passes", away), Some(0.0))
    assertEquals(s.derived.score, Map(home -> 1, away -> 0))
    assertEquals(s.derived.yellowCards, Map(player(away, 4) -> 1))
  }

  test("player numbers are credited to the player") {
    assertEquals(Engine.player(s, "passes", player(home, 2)).value, Some(1.0))
    assertEquals(Engine.player(s, "goals", player(home, 9)).value, Some(1.0))
    intercept[IllegalArgumentException](Engine.player(s, "possession_share", player(home, 2)))
  }

  test("team shares use both teams; undefined ratios are empty, not zero") {
    assertEquals(team("possession_share"), Some(100.0))
    assertEquals(team("possession_share", away), Some(0.0))
    assertEquals(team("pass_completion", away), None)
  }

  test("windows pick only events in their minutes") {
    val minute0 = Window(Some(1), 0, 1)
    val minute1 = Window(Some(1), 1, 2)
    assertEquals((team("passes", w = minute0), team("shots", w = minute0)), (Some(3.0), Some(0.0)))
    assertEquals((team("passes", w = minute1), team("shots", w = minute1)), (Some(0.0), Some(1.0)))
    assertEquals(
      Engine.series(s, "passes", Holder.Team(home), 1),
      Vector(0 -> Some(3.0), 1 -> Some(0.0), 2 -> Some(0.0))
    )
  }

  test("evidence lists exactly the events behind a number") {
    assertEquals(Engine.evidence(s, "passes", Holder.Team(home)), Vector(p1, p2, p3).map(_.eventId))
    assertEquals(
      Engine.evidence(s, "passes_completed", Holder.Team(home)),
      Vector(p1, p2).map(_.eventId)
    )
    assertEquals(Engine.evidence(s, "goals", Holder.Team(home)), Vector(goal.eventId))
    assertEquals(
      Engine.evidence(s, "pass_completion", Holder.Team(home)),
      Vector(p1, p2, p3).map(_.eventId)
    )
  }

  test("a correction to an outcome updates the aggregates") {
    val fixed = Engine.apply(s, update(p3.copy(outcome = Some(Outcome.Complete))))
    assertEquals(Engine.team(fixed, "passes_completed", home).value, Some(3.0))
    assertEquals(Engine.team(fixed, "pass_completion", home).value, Some(100.0))
    assertEquals(
      Engine.team(fixed, "passes", home).value,
      Some(3.0),
      "an update replaces, it does not add"
    )
  }

  test("a correction that moves an event in time moves its contribution to the new minute") {
    val moved = Engine.apply(s, update(p1.copy(periodClockS = 65)))
    assertEquals(Engine.team(moved, "passes", home, Window(Some(1), 0, 1)).value, Some(2.0))
    assertEquals(Engine.team(moved, "passes", home, Window(Some(1), 1, 2)).value, Some(1.0))
  }

  test("a delete removes the event, its numbers and its effect on the score") {
    val gone = Engine.apply(s, delete(goal.eventId))
    assertEquals(Engine.team(gone, "goals", home).value, Some(0.0))
    assertEquals(Engine.team(gone, "xg", home).value, Some(0.0))
    assertEquals(gone.derived.score, Map(home -> 0, away -> 0))
    assertEquals(Engine.apply(gone, delete(goal.eventId)), gone, "deleting twice is harmless")
  }

  test("a red card and a substitution change who is on the pitch") {
    val red = foul(6, away, 5, (40, 30), 200, Card.Red, player(home, 9))
    val sub = event(
      7,
      EventType.Substitution,
      home,
      Some(2),
      None,
      300,
      details = Some(SubstitutionDetails(player(home, 2), player(home, 12)))
    )
    val d = Engine.replay(feed ++ Vector(red, sub).map(create)).derived
    assertEquals(d.onPitch(away).size, 10)
    assert(!d.onPitch(away).contains(player(away, 5)))
    assert(d.onPitch(home).contains(player(home, 12)) && !d.onPitch(home).contains(player(home, 2)))
    assertEquals(d.sentOff, Set(player(away, 5)))
  }

  test("tracking metrics fall back to event estimates and say so") {
    val v = Engine.player(s, "distance_covered", player(home, 2))
    assertEquals(v.basis, Basis.EventEstimate)
    assertEquals((v.via, v.capabilityLevel, v.value), (Some("involvements"), 0, Some(1.0)))
    assertEquals(Engine.team(s, "passes", home).basis, Basis.EventData)
  }
