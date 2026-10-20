package smi

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import smi.engine.*
import smi.model.*

/** Incremental updates and deletes must land in the same state as recomputing from scratch. */
class EnginePropertySuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  private lazy val feed = GeneratedMatches.generate(5).envelopes
  private lazy val base = Engine.replay(feed)
  private lazy val ids = base.ordered.map(_.event.eventId)
  private lazy val header = feed.filter(e => e.payloadType != PayloadType.Event)

  private enum Change:
    case Flip, Remove, Restore
    case Shift(seconds: Double)
    case Move(dx: Double)

  private val change: Gen[(Int, Change)] = for
    i <- Gen.choose(0, 1600)
    c <- Gen.oneOf(
      Gen.const(Change.Flip),
      Gen.choose(-150.0, 150.0).map(Change.Shift(_)),
      Gen.choose(-8.0, 8.0).map(Change.Move(_)),
      Gen.const(Change.Remove),
      Gen.const(Change.Restore)
    )
  yield (i, c)

  private def corrected(e: Event, c: Change): Event = c match
    case Change.Flip =>
      e.copy(outcome = e.outcome.map {
        case Outcome.Complete => Outcome.Incomplete
        case Outcome.Goal     => Outcome.Saved
        case Outcome.Saved    => Outcome.Goal
        case other            => other
      })
    case Change.Shift(d) => e.copy(periodClockS = math.max(0, e.periodClockS + d))
    case Change.Move(d)  => e.copy(x = e.x.map(x => math.max(0, math.min(105, x + d))))
    case _               => e

  property("any mix of corrections and deletes equals a full recompute") {
    Prop.forAll(Gen.listOfN(40, change)) { changes =>
      val original = base.events.map((id, e) => id -> e.event)
      val (state, _) = changes.foldLeft((base, Map.empty[EventId, Event])) {
        case ((s, removed), (i, c)) =>
          val id = ids(i % ids.size)
          c match
            case Change.Remove =>
              (
                Engine.apply(s, EventKit.delete(id)),
                removed.updated(id, s.events.get(id).fold(original(id))(_.event))
              )
            case Change.Restore =>
              removed
                .get(id)
                .fold((s, removed))(e => (Engine.apply(s, EventKit.create(e)), removed - id))
            case _ =>
              s.events
                .get(id)
                .fold((s, removed))(e =>
                  (Engine.apply(s, EventKit.update(corrected(e.event, c))), removed)
                )
      }
      val recomputed = Engine.replay(header ++ state.ordered.map(e => EventKit.create(e.event)))
      assertEquals(state.events, recomputed.events)
      assertEquals(state.buckets.keySet, recomputed.buckets.keySet)
      state.buckets.foreach { (k, b) =>
        val r = recomputed.buckets(k)
        assertEquals(b.keySet, r.keySet, k.toString)
        b.foreach((key, v) => assertEqualsDouble(v, r(key), 1e-6, s"$k $key"))
      }
      assertEquals(state.derived, recomputed.derived)
      true
    }
  }
