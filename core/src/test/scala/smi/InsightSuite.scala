package smi

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import smi.engine.{Engine, Window}
import smi.insight.Rederive
import smi.model.*

/** Every insight is schema-valid and re-derivable from its evidence events alone. */
class InsightSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private val runs = InsightRuns.default

  test("every insight from 10 matches validates against insight.schema.json and round-trips") {
    val schema = SchemaFiles.schema("insight.schema.json")
    for (m, s) <- runs.take(10); i <- s.out do
      val json = i.asJson.noSpaces
      assertEquals(SchemaFiles.errors(schema, json), Nil, s"seed ${m.seed} ${i.insightId.value}")
      assertEquals(decode[Insight](json), Right(i))
  }

  test("every insight from 20 matches is re-derived exactly from its evidence events") {
    for (m, s) <- runs.take(20); i <- s.ranked do
      val events = (id: EventId) => s.engine.events.get(id).map(_.event)
      assertEquals(Rederive.check(i, events), Right(()), s"seed ${m.seed}")
  }

  test("pressure counts in a pressure shift agree with the engine's window query") {
    val shifts = for (_, s) <- runs.take(20); i <- s.ranked if i.kind == InsightKind.PressureShift
    yield (s, i)
    assert(shifts.nonEmpty)
    shifts.foreach { (s, i) =>
      def fact(k: String) = i.facts(k).flatMap(_.asNumber).map(_.toDouble).get
      val w = Window(Some(i.period), fact("before_to_minute"), fact("after_to_minute"))
      assertEquals(
        Engine.team(s.engine, "pressures", i.teams.head, w).value,
        Some(fact("after_pressures")),
        i.insightId.value
      )
    }
  }

  test("a sensible number of insights per match, and every kind shows up") {
    val counts = runs.map(_._2.ranked.size)
    val mean = counts.sum.toDouble / counts.size
    val byKind = runs.flatMap(_._2.ranked).groupMapReduce(_.kind)(_ => 1)(_ + _)
    println(
      f"insights per match: mean $mean%.1f, min ${counts.min}, max ${counts.max}; by kind ${byKind.map((k, n) => s"${InsightKind.wire(k)} $n").mkString(", ")}"
    )
    assert(mean >= 15 && mean <= 80, s"mean $mean")
    val expected =
      InsightKind.values.toSet - InsightKind.PlayerSpotlight - InsightKind.TacticalShift
    assertEquals(expected -- byKind.keySet, Set.empty[InsightKind])
  }

  test("ranking puts goals and red cards above routine milestones") {
    runs.take(10).foreach { (_, s) =>
      val ranked = s.ranked
      val lastBig =
        ranked.lastIndexWhere(i => i.kind == InsightKind.Goal || i.kind == InsightKind.RedCard)
      val firstMilestone =
        ranked.indexWhere(i => i.whyItMatters.code.value == "player_pass_milestone")
      if lastBig >= 0 && firstMilestone >= 0 then assert(lastBig < firstMilestone)
    }
  }
