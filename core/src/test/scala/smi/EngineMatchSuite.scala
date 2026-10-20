package smi

import java.nio.file.{Files, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import smi.engine.*
import smi.generator.{Calibration, GeneratorConfig, MatchGenerator, Scenario}
import smi.raw.{Adapter, RawEmitter}

/** The engine on whole matches: it agrees with independent counts, and through the raw feed. */
class EngineMatchSuite extends FunSuite:
  private val counted = Vector("goals", "shots", "passes", "passes_completed", "pressures", "fouls")

  test("engine totals equal the generator's own summary on 10 matches") {
    GeneratedMatches.default.take(10).foreach { m =>
      val s = Engine.replay(m.envelopes)
      s.teams.foreach { t =>
        val sum = m.summary
        assertEquals(
          Engine.team(s, "goals", t).value,
          Some(sum.goalsFor(t).toDouble),
          s"seed ${m.seed} goals"
        )
        assertEquals(
          Engine.team(s, "shots", t).value,
          Some(sum.shots.getOrElse(t, 0).toDouble),
          s"seed ${m.seed} shots"
        )
        assertEquals(
          Engine.team(s, "passes", t).value,
          Some(sum.passes.getOrElse(t, 0).toDouble),
          s"seed ${m.seed} passes"
        )
      }
      val score = s.teams.map(t => s.derived.score(t))
      assertEquals(score.sum, m.summary.totalGoals, s"seed ${m.seed} score")
    }
  }

  test("the same match through the raw feed and adapter gives the same numbers") {
    val canonical = GeneratedMatches.generate(7).envelopes
    val raw = RawEmitter().emit(canonical).fold(e => fail(e), identity)
    val (_, out) = Adapter().run(raw)
    val direct = Engine.replay(canonical)
    val adapted = Engine.replay(out.envelopes)
    direct.teams.foreach { t =>
      counted.foreach(id =>
        assertEquals(Engine.team(adapted, id, t).value, Engine.team(direct, id, t).value, s"$id $t")
      )
      assertEqualsDouble(
        Engine.team(adapted, "xg", t).value.get,
        Engine.team(direct, "xg", t).value.get,
        0.01
      )
    }
    assertEquals(adapted.derived.score, direct.derived.score)
  }

  test("over 50 unscripted matches, mean xG is within 30% of mean goals") {
    val baseline = Calibration.baseline(Scenario.default)
    val states = (1L to 50L).map(seed =>
      Engine.replay(MatchGenerator.generate(GeneratorConfig(seed, baseline)))
    )
    val xg =
      states.map(s => s.teams.flatMap(t => Engine.team(s, "xg", t).value).sum).sum / states.size
    val goals = states.map(s => s.derived.score.values.sum).sum.toDouble / states.size
    println(f"mean xG $xg%.2f vs mean goals $goals%.2f per match")
    assert(math.abs(xg - goals) <= 0.3 * goals, f"xG $xg%.2f vs goals $goals%.2f")
  }

  test("every metric declares a consistent requirement, fallback and basis") {
    Metrics.all.foreach { m =>
      assert(m.id.matches("^[a-z][a-z0-9_]{1,40}$"), s"${m.id} must be a valid metric key")
      m.fallback.foreach(f =>
        assert(Metrics.byId.contains(f) && Metrics(f).requires < m.requires, s"${m.id} fallback $f")
      )
      if m.requires > 0 then
        assert(m.fallback.isDefined, s"${m.id} needs tracking and must have a fallback")
      m.calc match
        case Calc.Ratio(num, den, _) =>
          assert(
            Metrics.additive.exists(_.id == num.metric) && Metrics.additive.exists(
              _.id == den.metric
            ),
            m.id
          )
        case _ => ()
    }
  }

  test("the engine never reads generator code") {
    val dir = Paths.get("src/main/scala/smi/engine")
    val sources = Files.list(dir).iterator.asScala.toVector
    assert(sources.nonEmpty)
    sources.foreach(p =>
      assert(!Files.readString(p).contains("smi.generator"), s"$p imports the generator")
    )
  }
