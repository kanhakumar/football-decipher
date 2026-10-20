package smi

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import smi.generator.*
import smi.model.*

class GeneratorSuite extends FunSuite:
  private def ndjson(seed: Long, scenario: Scenario = Scenario.default) =
    MatchGenerator.toNdjson(MatchGenerator.generate(GeneratorConfig(seed, scenario)))

  test("the same seed and scenario give byte-identical NDJSON; another seed differs") {
    val a = ndjson(42)
    assertEquals(ndjson(42), a)
    assertNotEquals(ndjson(43), a)
  }

  test("the scenario changes the match") {
    assertNotEquals(ndjson(42, Scenario.default.copy(beats = None)), ndjson(42))
  }

  test("the default scenario YAML loads with its phase and beats") {
    val s = Scenario.default
    assertEquals(s.phases.map(_.size), Some(1))
    assertEquals(
      s.beatList.map(b => (b.kind, b.team, b.minute)),
      List(
        (BeatKind.CounterGoal, Side.Away, 34.0),
        (BeatKind.RedCard, Side.Home, 78.0)
      )
    )
  }

  test("a malformed scenario is rejected") {
    assert(Scenario.parse("name: x\nteams: { home: { press: 1 } }").isLeft)
    assert(Scenario.parse(Scenario.default.toString).isLeft)
  }

  test("every line of 5 matches validates against canonical.schema.json") {
    val schema = SchemaFiles.schema("canonical.schema.json")
    for
      seed <- 1L to 5L
      (line, i) <- ndjson(seed).split("\n").zipWithIndex
    do assertEquals(SchemaFiles.errors(schema, line), Nil, s"seed $seed line ${i + 1}")
  }

  test("every line decodes strictly and re-encodes to the same bytes") {
    ndjson(42).split("\n").foreach { line =>
      assertEquals(decode[Envelope](line).map(_.asJson.noSpaces), Right(line))
    }
  }

  test("every event type the generator promises appears across 10 matches") {
    val seen = GeneratedMatches.default.take(10).flatMap(_.events.map(_.`type`)).toSet
    val expected = Set(
      EventType.PeriodStart,
      EventType.PeriodEnd,
      EventType.Pass,
      EventType.Shot,
      EventType.Pressure,
      EventType.Tackle,
      EventType.Dribble,
      EventType.Interception,
      EventType.Clearance,
      EventType.Block,
      EventType.GoalkeeperAction,
      EventType.PossessionChange,
      EventType.Foul,
      EventType.SetPiece,
      EventType.Substitution,
      EventType.Offside
    )
    assertEquals(expected -- seen, Set.empty[EventType])
  }

  test("loose plausibility over 20 seeds (exact targets are calibrated in step 5)") {
    val ms = GeneratedMatches.default.take(20).map(_.summary)
    def avg(f: MatchSummary => Double) = ms.map(f).sum / ms.size
    val (events, goals, shots, completion) =
      (avg(_.events), avg(_.totalGoals), avg(_.totalShots), avg(_.passCompletion))
    val (fouls, yellows, reds) = (avg(_.fouls), avg(_.yellowCards), avg(_.redCards.size))
    println(
      f"avg events $events%.0f goals $goals%.2f shots $shots%.1f completion ${completion * 100}%.1f%% fouls $fouls%.1f yellow $yellows%.1f red $reds%.2f"
    )
    val bySituation =
      ms.flatMap(_.goals.flatMap(_.situation)).groupMapReduce(ShotSituation.wire)(_ => 1)(_ + _)
    println(s"goals by situation over ${ms.size} matches: $bySituation")
    assert(events >= 1500 && events <= 2500, s"events $events")
    assert(goals >= 1.5 && goals <= 4.0, s"goals $goals")
    assert(shots >= 12 && shots <= 35, s"shots $shots")
    assert(completion >= 0.75 && completion <= 0.90, s"completion $completion")
  }
