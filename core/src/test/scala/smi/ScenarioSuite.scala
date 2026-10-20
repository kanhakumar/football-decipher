package smi

import munit.FunSuite
import smi.GeneratedMatches.Match
import smi.generator.*
import smi.model.*

/** The scripted story must show up: beats near their minute, the press during its phase. */
class ScenarioSuite extends FunSuite:
  private val home = Squads.home.teamId
  private val away = Squads.away.teamId

  private def counterGoal(m: Match): Option[Double] =
    m.summary.goals
      .filter(g => g.team == away && g.situation.contains(ShotSituation.Counter))
      .map(_.minute)
      .minByOption(t => math.abs(t - 34))

  private def homeRed(m: Match): Option[Double] =
    m.summary.redCards.filter(_.team == home).map(_.minute).minByOption(t => math.abs(t - 78))

  private def hits(ms: Vector[Match], find: Match => Option[Double], target: Double) =
    ms.flatMap(find).filter(t => math.abs(t - target) <= 5)

  private def report(name: String, found: Vector[Double], target: Double): Unit =
    val offsets = found.map(t => math.abs(t - target)).sorted
    val median = if offsets.isEmpty then Double.NaN else offsets(offsets.size / 2)
    println(f"$name: ${found.size}/50 within 5 min, median offset $median%.1f min")

  test("the away counter-attack goal lands within 5 minutes of 34' in at least 90% of seeds") {
    val found = hits(GeneratedMatches.default, counterGoal, 34)
    report("counter goal", found, 34)
    assert(found.size >= 45, s"${found.size}/50")
  }

  test("the home red card lands within 5 minutes of 78' in at least 90% of seeds") {
    val found = hits(GeneratedMatches.default, homeRed, 78)
    report("red card", found, 78)
    assert(found.size >= 45, s"${found.size}/50")
  }

  test("without beats, neither story happens at anything like that rate") {
    val counters = hits(GeneratedMatches.noBeats, counterGoal, 34).size
    val reds = hits(GeneratedMatches.noBeats, homeRed, 78).size
    println(s"control: counter goal $counters/50, red card $reds/50")
    assert(counters <= 10, s"$counters/50")
    assert(reds <= 10, s"$reds/50")
  }

  test("home presses harder during the 55-70 high-press phase") {
    def rate(inPhase: Boolean) =
      val evs = GeneratedMatches.default.flatMap(_.events).filter { e =>
        val m = MatchSummary.minuteOf(e)
        e.period == 2 && (m >= 55 && m < 70) == inPhase
      }
      val pressures = evs.count(e => e.`type` == EventType.Pressure && e.teamId == home)
      val awayPasses = evs.count(e => e.`type` == EventType.Pass && e.teamId == away)
      pressures.toDouble / awayPasses
    val (during, outside) = (rate(true), rate(false))
    println(f"home pressures per away pass: $during%.3f in 55-70, $outside%.3f rest of second half")
    assert(during >= outside * 1.4, s"$during vs $outside")
  }
