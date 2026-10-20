package smi

import munit.FunSuite
import smi.generator.*

/** The calibration gate (ADR-008): the unscripted baseline must hit every target band. */
class CalibrationSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private lazy val run = Calibration.run(Calibration.DefaultSeeds, Scenario.default)

  test(s"every baseline metric is in range over ${Calibration.DefaultSeeds} seeds") {
    println(Calibration.table(run))
    val out = run.baseResults.filterNot(_.inRange)
    assert(out.isEmpty, out.map(r => s"${r.metric.name} ${r.valueText}").mkString(", "))
  }

  test("the story scenario only adds its scripted goal; everything else stays in range") {
    val goals = "goals per match"
    val others = run.storyResults.filter(r => r.metric.name != goals && !r.inRange)
    assert(others.isEmpty, others.map(r => s"${r.metric.name} ${r.valueText}").mkString(", "))
    def value(rs: Vector[Calibration.Result]) = rs.find(_.metric.name == goals).get.value
    val extra = value(run.storyResults) - value(run.baseResults)
    assert(extra >= 0.5 && extra <= 1.5, f"story adds $extra%.2f goals per match")
  }
