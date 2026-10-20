package smi

import munit.FunSuite
import smi.insight.{MomentState, Moments}
import smi.model.*

/** Hand-made sequences trigger each detector exactly when they should. */
class DetectorSuite extends FunSuite:
  import EventKit.*

  private def pos(n: Int) = Some(PossessionId.unsafe(f"POS_$n%04d"))
  private def run(events: Event*): MomentState = Moments.run(header ++ events.map(create))
  private def codes(s: MomentState, kind: InsightKind) =
    s.ranked.filter(_.kind == kind).sortBy(_.tMatchS).map(_.whyItMatters.code.value)
  private def fact(i: Insight, k: String) = i.facts(k).get

  private def change(
      id: Long,
      to: TeamId,
      cause: PossessionCause,
      clockS: Double,
      possession: Int
  ) =
    val from = if to == home then away else home
    event(
      id,
      EventType.PossessionChange,
      to,
      None,
      None,
      clockS,
      details = Some(PossessionChangeDetails(from, to, cause))
    )
      .copy(possessionId = pos(possession))

  test("goals: takes the lead, then the equaliser") {
    val s = run(
      shot(1, home, 9, (99, 34), 100, Outcome.Goal).copy(possessionId = pos(1)),
      shot(2, away, 9, (99, 34), 300, Outcome.Goal).copy(possessionId = pos(2))
    )
    assertEquals(codes(s, InsightKind.Goal), Vector("takes_lead", "equaliser"))
  }

  /** Ball won by interception at `startX`, two passes, a goal 7 s later. */
  private def breakaway(startX: Double) = run(
    change(10, away, PossessionCause.Interception, 200, 2),
    pass(11, away, 6, (startX, 34), (startX + 25, 34), 200.5, complete = true)
      .copy(possessionId = pos(3)),
    pass(12, away, 8, (startX + 25, 34), (90, 34), 203, complete = true)
      .copy(possessionId = pos(3)),
    shot(13, away, 9, (95, 34), 207, Outcome.Goal).copy(possessionId = pos(3))
  )

  test("a ball won in the own half and scored within 15 s is a counter-attack goal") {
    val s = breakaway(30)
    val goal = s.ranked.find(_.kind == InsightKind.Goal).get
    assertEquals(goal.whyItMatters.code.value, "counter_attack_goal")
    assertEquals(
      (
        fact(goal, "possession_passes").asNumber.flatMap(_.toInt),
        fact(goal, "won_ball_by").asString
      ),
      (Some(2), Some("interception"))
    )
    assertEquals(
      codes(breakaway(60), InsightKind.Goal),
      Vector("takes_lead"),
      "won in the opponent half: not a counter"
    )
  }

  test("a second yellow is a red card insight that says so") {
    val s = run(
      foul(1, home, 4, (50, 30), 100, Card.Yellow, player(away, 9)),
      foul(2, home, 4, (50, 30), 400, Card.Red, player(away, 9))
    )
    val red = s.ranked.find(_.kind == InsightKind.RedCard).get
    assertEquals(
      (fact(red, "second_yellow").asBoolean, fact(red, "players_left").asNumber.flatMap(_.toInt)),
      (Some(true), Some(10))
    )
    assertEquals(red.whyItMatters.code.value, "down_to_ten")
  }

  test("a saved big chance is a key chance; a long-range miss is not") {
    val s = run(
      shot(1, home, 9, (99, 34), 100, Outcome.Saved),
      shot(2, home, 9, (80, 34), 200, Outcome.OffTarget)
    )
    assertEquals(codes(s, InsightKind.KeyChance), Vector("big_chance_saved"))
  }

  test("a player's 50th completed pass is a milestone, the 49th is not") {
    def passes(n: Int) =
      (1 to n).map(i => pass(i, home, 6, (40, 34), (50, 34), i * 10.0, complete = true))
    assertEquals(codes(run(passes(49)*), InsightKind.Milestone), Vector())
    assertEquals(codes(run(passes(50)*), InsightKind.Milestone), Vector("player_pass_milestone"))
  }

  /** Away passes `passes` per minute and home presses `pressures` per minute, minute by minute. */
  private def minutes(from: Int, to: Int, passes: Int, pressures: Int, start: Long): Vector[Event] =
    (from until to).toVector
      .flatMap { m =>
        val ps = (0 until passes).map(i =>
          pass(start + m * 100 + i, away, 6, (40, 34), (45, 30), m * 60 + i * 5.0, complete = true)
        )
        val pr = (0 until pressures).map(i =>
          event(
            start + m * 100 + 50 + i,
            EventType.Pressure,
            home,
            Some(8),
            Some((60, 34)),
            m * 60 + 2.0 + i * 5,
            details = Some(PressureDetails(player(away, 6)))
          )
        )
        ps ++ pr
      }
      .sortBy(_.periodClockS)

  test("a pressing burst after a quiet spell is a pressure shift; a steady press is not") {
    val burst = run((minutes(0, 15, 6, 1, 1000) ++ minutes(15, 21, 5, 3, 1000))*)
    assert(
      burst.ranked.exists(i => i.kind == InsightKind.PressureShift && i.teams.head == home),
      burst.ranked.map(_.kind).toString
    )
    val steady = run(minutes(0, 21, 6, 1, 1000)*)
    assert(!steady.ranked.exists(_.kind == InsightKind.PressureShift))
  }

  test("a jump in passes per minute is a rhythm change") {
    val s = run((minutes(0, 10, 6, 0, 1000) ++ minutes(10, 21, 10, 0, 1000))*)
    assertEquals(codes(s, InsightKind.RhythmChange).headOption, Some("tempo_rises"))
  }
