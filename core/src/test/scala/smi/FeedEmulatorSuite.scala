package smi

import cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import smi.model.*
import smi.raw.*

/** The emulated feed must be messy, and the adapter must recover the clean match from it. */
class FeedEmulatorSuite extends CatsEffectSuite:
  private lazy val canonical = GeneratedMatches.generate(11).envelopes
  private lazy val kickoff = canonical.collectFirst {
    case Envelope(_, _, _, _, _, _, _, Payload.Meta(m)) => m.kickoffUtc.toInstant
  }.get
  private lazy val raw = RawEmitter().emit(canonical).fold(e => fail(e), identity)
  private def emulate(seed: Long) = FeedEmulator.emulate(raw, kickoff, EmulatorConfig(seed))
  private lazy val (deliveries, mess) = emulate(3)

  test("the same emulator seed gives the same deliveries; another seed differs") {
    assertEquals(emulate(3)._1, deliveries)
    assertNotEquals(emulate(4)._1, deliveries)
  }

  test("the feed is messy: delays, reordering, corrections, phantoms and a dropout") {
    println(
      s"emulator: ${mess.delayed} delayed, ${mess.reordered} reordered, corrections ${mess.corrections}, phantoms ${mess.phantoms}, dropout ${mess.dropout}"
    )
    assert(mess.delayed > 0)
    assert(mess.reordered > 0)
    assertEquals(mess.corrections.size, 3)
    assertEquals(mess.phantoms.size, 2)
    val (start, end) = mess.dropout
    assert(end - start >= 20_000)
    assert(
      !deliveries.exists(d => d.atMs >= start && d.atMs < end),
      "nothing arrives during the dropout"
    )
    assert(
      deliveries.count(d => d.atMs >= end && d.atMs < end + 50) > 1,
      "a burst arrives when it ends"
    )
  }

  test("the adapter recovers the clean match from the messy feed") {
    val (messy, out) = Adapter().run(deliveries.map(_.message))
    assertEquals(out.issues.filter(i => i.kind == "late" || i.kind == "rejected"), Vector.empty)

    // The clean truth: the original feed with corrections applied in place and no phantoms.
    val (truth, _) = Adapter().run(FeedEmulator.truth(raw, deliveries, mess))

    assertEquals(messy.published.keySet, truth.published.keySet)
    assertEquals(messy.published, truth.published)
    mess.phantoms.foreach(id => assert(!messy.published.contains(id), s"phantom $id"))

    val created = out.envelopes.collect {
      case Envelope(_, _, _, _, Op.Create, _, _, Payload.Ev(e)) => e.wallclockUtc.toInstant
    }
    assertEquals(created, created.sorted, "events are published in time order")
    val updates = out.envelopes.count(e => e.op == Op.Update && e.payloadType == PayloadType.Event)
    assertEquals(updates, mess.corrections.size)
  }

  test("replay runs at the requested speed") {
    val sample = Vector(0L, 1_000L, 2_000L).map(t => Delivery(t, raw.head))
    for
      start <- IO.monotonic
      got <- FeedEmulator.stream(sample, speed = 20).compile.toVector
      took <- IO.monotonic.map(_ - start)
    yield
      assertEquals(got.size, 3)
      assert(took >= 90.millis && took < 1.second, s"took $took for 2 s of feed at 20x")
  }

  test("speeds outside 1x to 20x are refused") {
    intercept[IllegalArgumentException](FeedEmulator.stream(Vector.empty, speed = 25))
  }
