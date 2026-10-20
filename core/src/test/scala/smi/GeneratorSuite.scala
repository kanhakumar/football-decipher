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
