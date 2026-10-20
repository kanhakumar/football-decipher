package smi

import munit.FunSuite
import smi.engine.*
import smi.model.*

class EnrichSuite extends FunSuite:
  import EventKit.*

  private def xg(
      x: Double,
      y: Double,
      part: BodyPart = BodyPart.RightFoot,
      s: ShotSituation = ShotSituation.OpenPlay
  ) =
    Enrich.xg(x, y, Some(part), s)

  test("xG: penalties are fixed, and closer and more central shots are worth more") {
    assertEquals(xg(94, 34, s = ShotSituation.Penalty), 0.76)
    val sixYards = xg(99, 34)
    val spot = xg(94, 34)
    val edge = xg(85, 34)
    val long = xg(75, 34)
    println(f"xG: 6 m $sixYards%.3f, spot $spot%.3f, 20 m $edge%.3f, 30 m $long%.3f")
    assert(sixYards > spot && spot > edge && edge > long)
    assert(sixYards > 0.2 && sixYards < 0.5, s"6 m: $sixYards")
    assert(spot > 0.08 && spot < 0.25, s"penalty spot: $spot")
    assert(edge > 0.02 && edge < 0.08, s"20 m: $edge")
    assert(xg(104, 20) < xg(91, 34), "a tight angle is worth less than a central shot further out")
    assert(xg(94, 34, BodyPart.Head) < spot, "headers are harder")
    assert(xg(85, 34, s = ShotSituation.FreeKick) < edge, "direct free kicks are harder")
  }

  test("goal-mouth angle shrinks toward the byline and with distance") {
    assert(Zones.goalAngle(99, 34) > Zones.goalAngle(94, 34))
    assert(Zones.goalAngle(104, 10) < Zones.goalAngle(94, 34))
    assertEqualsDouble(Zones.goalAngle(105 - 3.66, 34), math.Pi / 2, 1e-9)
  }

  test("zones: thirds, box edges, zone 14 and the 18-zone grid") {
    assertEquals(
      (Zones.third(34.9), Zones.third(35), Zones.third(69.9), Zones.third(70)),
      (0, 1, 1, 2)
    )
    assert(Zones.inBox(88.5, 34) && !Zones.inBox(88.4, 34))
    assert(Zones.inBox(95, 13.84) && !Zones.inBox(95, 13.8))
    assert(Zones.inZone14(80, 34) && !Zones.inZone14(90, 34) && !Zones.inZone14(80, 10))
    assertEquals((Zones.zone(0, 0), Zones.zone(104.9, 67.9), Zones.zone(52.5, 34)), (0, 17, 10))
  }

  test("progressive passes need a real gain toward goal") {
    def prog(from: (Double, Double), to: (Double, Double)) =
      Enrich(pass(1, home, 4, from, to, 0, complete = true)).progressive
    assert(prog((60, 34), (75, 34)), "15 m gain from 45 m out")
    assert(!prog((30, 34), (45, 34)), "15 m gain from 75 m out is not enough")
    assert(!prog((60, 34), (65, 34)), "5 m gain")
    assert(
      !Enrich(pass(1, home, 4, (60, 34), (75, 34), 0, complete = false)).progressive,
      "only completed passes"
    )
  }

  test("final-third and box entries") {
    val entry = Enrich(pass(1, home, 4, (60, 34), (75, 30), 0, complete = true))
    assert(entry.finalThirdEntry && !entry.boxEntry)
    val box = Enrich(pass(1, home, 4, (80, 10), (95, 34), 0, complete = true))
    assert(box.boxEntry && !box.finalThirdEntry)
    assert(!Enrich(pass(1, home, 4, (80, 10), (95, 34), 0, complete = false)).boxEntry)
  }

  test("pass difficulty: a long forward ball under pressure is harder than a short square one") {
    val easy = Enrich(pass(1, home, 4, (40, 34), (40, 44), 0, complete = true))
    val hard = Enrich(pass(2, home, 4, (40, 34), (90, 30), 0, complete = true, pressured = true))
    assert(easy.passDifficulty.get < 0.1, easy.toString)
    assert(hard.passDifficulty.get > 0.6, hard.toString)
  }
