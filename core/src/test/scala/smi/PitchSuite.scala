package smi

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class PitchSuite extends ScalaCheckSuite:
  property("every point inside the bounds is on the pitch") {
    forAll(Gen.choose(0.0, Pitch.Length), Gen.choose(0.0, Pitch.Width)) { (x, y) =>
      Pitch.contains(x, y)
    }
  }

  test("points past the goal line are off the pitch") {
    assert(!Pitch.contains(Pitch.Length + 0.1, 10))
  }
