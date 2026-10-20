package smi

import io.circe.parser.decode
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import smi.generator.*
import smi.model.*

class GeneratorSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(8)

  private def generate(seed: Long) = MatchGenerator.generate(GeneratorConfig(seed))
  private lazy val seed42 = generate(42)
  private lazy val seed42Lines = MatchGenerator.toNdjson(seed42).split("\n").toVector

  private def events(envs: Vector[Envelope]): Vector[Event] =
    envs.collect { case Envelope(_, _, _, _, Op.Create, _, _, Payload.Ev(e)) => e }

  test("the same seed gives byte-identical NDJSON; another seed differs") {
    val a = MatchGenerator.toNdjson(generate(42))
    assertEquals(MatchGenerator.toNdjson(generate(42)), a)
    assertNotEquals(MatchGenerator.toNdjson(generate(43)), a)
  }

  test("every line validates against canonical.schema.json") {
    val schema = SchemaFiles.schema("canonical.schema.json")
    seed42Lines.zipWithIndex.foreach { (line, i) =>
      assertEquals(SchemaFiles.errors(schema, line), Nil, s"line ${i + 1}")
    }
  }

  test("every line decodes strictly and re-encodes to the same bytes") {
    seed42Lines.foreach { line =>
      assertEquals(decode[Envelope](line).map(_.asJson.noSpaces), Right(line))
    }
  }

  property("invariants hold for any seed") {
    Prop.forAll(Gen.choose(Long.MinValue, Long.MaxValue)) { seed =>
      val envs = generate(seed)
      val evs = events(envs)
      checkInvariants(envs, evs)
      true
    }
  }

  test("averages over 20 seeds are plausible for a passes-and-shots match") {
    val summaries = (1L to 20L).map(s => MatchSummary.of(generate(s)))
    def avg(f: MatchSummary => Double) = summaries.map(f).sum / summaries.size
    val goals = avg(_.totalGoals)
    val shots = avg(_.totalShots)
    val completion = avg(_.passCompletion)
    val minutes = avg(_.durationMinutes)
    val homeShare = avg(_.possessionShare(Squads.home.teamId))
    println(
      f"avg goals $goals%.2f shots $shots%.1f completion ${completion * 100}%.1f%% home share ${homeShare * 100}%.1f%% minutes $minutes%.1f"
    )
    assert(goals >= 1.5 && goals <= 4.0, s"goals $goals")
    assert(shots >= 15 && shots <= 35, s"shots $shots")
    assert(completion >= 0.78 && completion <= 0.90, s"completion $completion")
    assert(minutes >= 90 && minutes <= 100, s"minutes $minutes")
    assert(homeShare >= 0.45 && homeShare <= 0.55, s"home share $homeShare")
    summaries.foreach(s =>
      assert(
        s.possessionShare(Squads.home.teamId) >= 0.35 && s.possessionShare(
          Squads.home.teamId
        ) <= 0.65
      )
    )
  }

  private def checkInvariants(envs: Vector[Envelope], evs: Vector[Event]): Unit =
    assertEquals(envs.map(_.seq), envs.indices.map(_.toLong).toVector, "seq is 0..n-1")
    assertEquals(
      envs.map(_.emittedAt.toInstant).sorted,
      envs.map(_.emittedAt.toInstant),
      "emitted_at ordered"
    )

    assert(
      evs.forall(e => e.`type` == EventType.Pass || e.`type` == EventType.Shot),
      "passes and shots only"
    )
    assertEquals(evs.map(_.period).distinct, Vector(1, 2))
    def nonDecreasing(xs: Vector[Double]) = xs.zip(xs.drop(1)).forall(_ <= _)
    assert(nonDecreasing(evs.map(_.matchClockS)), "match clock")
    assert(nonDecreasing(evs.map(_.videoTsMs.toDouble)), "video_ts_ms")
    assert(nonDecreasing(evs.map(_.wallclockUtc.toInstant.toEpochMilli.toDouble)), "wallclock")
    evs.groupBy(_.period).foreach { (p, inPeriod) =>
      assert(nonDecreasing(inPeriod.map(_.periodClockS)), s"period $p clock")
      assert(inPeriod.last.periodClockS >= 45 * 60, s"period $p lasts 45 minutes")
    }
    assert(evs.filter(_.period == 2).forall(_.matchClockS >= 45 * 60), "second half match clock")

    val lineups = envs.collect { case Envelope(_, _, _, _, _, _, _, Payload.Team(l)) => l }
    assertEquals(lineups.map(_.starters.size), Vector(11, 11))
    val squadOf = lineups.flatMap(l => l.starters.map(_.playerId -> l.teamId)).toMap
    evs.foreach(e => assertEquals(e.playerId.flatMap(squadOf.get), Some(e.teamId), e.eventId.value))

    val finalMeta = envs.collect { case Envelope(_, _, _, _, _, _, _, Payload.Meta(m)) => m }.last
    assertEquals(finalMeta.status, MatchStatus.FullTime)
    val goals =
      evs.filter(_.outcome.contains(Outcome.Goal)).groupMapReduce(_.teamId.value)(_ => 1)(_ + _)
    assertEquals(finalMeta.score.toMap.filter(_._2 > 0), goals, "score equals goal events")

    // Spatial continuity: within a possession the next action starts where a completed pass ended.
    evs.zip(evs.drop(1)).foreach { (a, b) =>
      (a.details, a.outcome) match
        case (Some(p: PassDetails), Some(Outcome.Complete)) if a.possessionId == b.possessionId =>
          assertEquals(
            (b.x, b.y),
            (Some(p.endX), Some(p.endY)),
            s"${a.eventId.value} -> ${b.eventId.value}"
          )
          assertEquals(b.playerId, p.recipientId, "receiver acts next")
        case _ => ()
    }
