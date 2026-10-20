package smi

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import smi.generator.MatchSummary
import smi.model.*
import smi.raw.*

/** Raw provider feed: emitter and adapter round trips, and the adapter's edge cases. */
class RawFeedSuite extends FunSuite:
  private val registry = Registry.default
  private lazy val canonical = GeneratedMatches.generate(7).envelopes
  private lazy val raw = RawEmitter().emit(canonical).fold(e => fail(e), identity)

  private def run(feed: Seq[RawMessage]) = Adapter().run(feed)
  private def events(out: AdapterOutput) =
    out.envelopes.collect { case Envelope(_, _, _, _, Op.Create, _, _, Payload.Ev(e)) => e }

  /** Order-free view of a raw feed: each message's JSON, grouped by what it is about. */
  private def keyed(feed: Seq[RawMessage]): Map[(String, String), Vector[Json]] =
    feed
      .groupMap(m =>
        (
          RawKind.wire(m.kind),
          m.eventRef.map(_.toString).orElse(m.lineup.map(_.teamId.toString)).getOrElse("")
        )
      )(_.asJson)
      .view
      .mapValues(_.toVector)
      .toMap

  private def eventMessage(p: RawEvent => Boolean): RawMessage =
    raw.find(_.event.exists(p)).get

  private def withEvent(m: RawMessage)(f: RawEvent => RawEvent): RawMessage =
    m.copy(event = m.event.map(f))

  test("the registry loads our own type and qualifier tables") {
    assertEquals(registry.typeById.size, EventType.values.length)
    assertEquals(registry.typeIdOf.keySet, EventType.values.toSet)
    assertEquals(
      registry.qualifierIdOf.size,
      registry.qualifierById.size,
      "qualifier names are unique"
    )
  }

  test("raw -> canonical -> raw is lossless") {
    val (_, out) = run(raw)
    assertEquals(out.issues, Vector.empty)
    val back = RawEmitter().emit(out.envelopes).fold(e => fail(e), identity)
    assertEquals(keyed(back), keyed(raw))
  }

  test("canonical -> raw -> canonical keeps every event within raw precision") {
    val (_, out) = run(raw)
    val original = MatchSummary.events(canonical).map(e => e.eventId -> e).toMap
    val adapted = events(out)
    assertEquals(adapted.map(_.eventId).toSet, original.keySet)
    adapted.foreach { a =>
      val o = original(a.eventId)
      val ref = a.eventId.value
      assertEquals(
        (a.`type`, a.teamId, a.playerId, a.period, a.outcome),
        (o.`type`, o.teamId, o.playerId, o.period, o.outcome),
        ref
      )
      assertEquals(a.videoTsMs, o.videoTsMs, ref)
      assertEquals(a.wallclockUtc, o.wallclockUtc, ref)
      assert(math.abs(a.periodClockS - o.periodClockS) < 1.0, ref)
      assert(math.abs(a.matchClockS - o.matchClockS) < 2.0, ref)
      a.x.zip(o.x).foreach { case (ax, ox) => assert(math.abs(ax - ox) < 0.006, s"$ref x") }
      a.y.zip(o.y).foreach { case (ay, oy) => assert(math.abs(ay - oy) < 0.006, s"$ref y") }
      assert(
        closeJson(detailsJson(a), detailsJson(o)),
        s"$ref details ${detailsJson(a)} vs ${detailsJson(o)}"
      )
    }
  }

  test("adapter output validates against canonical.schema.json") {
    val schema = SchemaFiles.schema("canonical.schema.json")
    run(raw)._2.envelopes.foreach(env =>
      assertEquals(SchemaFiles.errors(schema, env.asJson.noSpaces), Nil, env.seq.toString)
    )
  }

  test("a delete removes a published event") {
    val target = eventMessage(_.typeId == registry.typeIdOf(EventType.Pass))
    val id = target.event.get.id
    val delete = RawMessage(
      RawKind.Event,
      RawAction.Delete,
      target.matchRef,
      target.lastModified,
      eventId = Some(id)
    )
    val (state, out) = run(raw :+ delete)
    assert(!state.published.contains(id))
    val deletes = out.envelopes.collect {
      case Envelope(_, _, _, _, Op.Delete, _, _, Payload.Delete(d)) => d.eventId
    }
    assertEquals(deletes, Vector(Mapping.eventId(id)))
  }

  test("an update to a published event becomes an update envelope") {
    val target = eventMessage(_.typeId == registry.typeIdOf(EventType.Pass))
    val fixed = withEvent(target)(e => e.copy(x = Some(50.0)))
    val (state, out) = run(raw :+ fixed)
    assertEquals(state.published(target.event.get.id).x, Some(52.5))
    assert(out.envelopes.exists(e => e.op == Op.Update && e.payloadType == PayloadType.Event))
  }

  test("out-of-order delivery within the reorder window is put back in order") {
    val swapped = raw
      .grouped(2)
      .flatMap(pair => if pair.forall(_.event.isDefined) then pair.reverse else pair)
      .toVector
    assert(
      FeedEmulator.reordered(swapped.map(Delivery(0, _))) > 100,
      "the test feed really is out of order"
    )
    val (state, out) = run(swapped)
    assertEquals(out.issues, Vector.empty)
    val stamps = events(out).map(_.wallclockUtc.toInstant)
    assertEquals(stamps, stamps.sorted)
    assertEquals(state.published, run(raw)._1.published)
  }

  test("a missing result qualifier is repaired with the default outcome") {
    val failed =
      eventMessage(e => e.typeId == registry.typeIdOf(EventType.Pass) && e.outcome.contains(0))
    val stripped = withEvent(failed)(e =>
      e.copy(qualifiers = e.qualifiers.filterNot(_.qid == registry.qualifierIdOf("result")))
    )
    val (state, out) = run(raw.map(m => if m == failed then stripped else m))
    assertEquals(state.published(failed.event.get.id).outcome, Some(Outcome.Incomplete))
    assert(
      out.issues.exists(i => i.kind == "repaired" && i.detail.contains("result")),
      out.issues.toString
    )
  }

  test("a pass missing a required qualifier is rejected, not guessed") {
    val pass = eventMessage(_.typeId == registry.typeIdOf(EventType.Pass))
    val stripped = withEvent(pass)(e =>
      e.copy(qualifiers = e.qualifiers.filterNot(_.qid == registry.qualifierIdOf("end_x")))
    )
    val (state, out) = run(raw.map(m => if m == pass then stripped else m))
    assert(!state.published.contains(pass.event.get.id))
    assert(
      out.issues.exists(i => i.kind == "rejected" && i.ref == s"event ${pass.event.get.id}"),
      out.issues.toString
    )
  }

  test("boundary coordinates map exactly; slightly outside is clamped, far outside rejected") {
    assertEquals(
      (Mapping.fromRawX(0), Mapping.fromRawX(100), Mapping.fromRawY(100)),
      (0.0, 105.0, 68.0)
    )
    assertEquals((Mapping.toRawX(105), Mapping.toRawY(68), Mapping.toRawY(0)), (100.0, 100.0, 0.0))
    val pass = eventMessage(_.typeId == registry.typeIdOf(EventType.Pass))
    val id = pass.event.get.id
    def adapt(x: Double) =
      val (state, out) =
        run(raw.map(m => if m == pass then withEvent(m)(_.copy(x = Some(x), y = Some(0))) else m))
      (state.published.get(id).flatMap(_.x), out.issues.filter(_.ref == s"event $id").map(_.kind))
    assertEquals(adapt(100), (Some(105.0), Vector()))
    assertEquals(adapt(100.6), (Some(105.0), Vector("repaired")))
    assertEquals(adapt(103), (None, Vector("rejected")))
  }

  test("unknown type and qualifier IDs are rejected") {
    val pass = eventMessage(_.typeId == registry.typeIdOf(EventType.Pass))
    val badType = withEvent(pass)(_.copy(typeId = 999))
    val other =
      eventMessage(e => e.typeId == registry.typeIdOf(EventType.Pass) && e.id != pass.event.get.id)
    val badQualifier =
      withEvent(other)(e => e.copy(qualifiers = RawQualifier(999, "x") :: e.qualifiers))
    val (_, out) =
      run(raw.map(m => if m == pass then badType else if m == other then badQualifier else m))
    val rejected = out.issues.filter(_.kind == "rejected").map(_.detail)
    assert(rejected.exists(_.contains("type ID 999")), rejected.toString)
    assert(rejected.exists(_.contains("qualifier ID 999")), rejected.toString)
  }

  private def detailsJson(e: Event): Json =
    e.details.map(_.asJson).filterNot(_.asObject.exists(_.isEmpty)).getOrElse(Json.Null)

  /** JSON equality with a small tolerance on numbers (raw coordinates have 2 decimals). */
  private def closeJson(a: Json, b: Json): Boolean =
    (a.asNumber, b.asNumber, a.asObject, b.asObject, a.asArray, b.asArray) match
      case (Some(x), Some(y), _, _, _, _) => math.abs(x.toDouble - y.toDouble) < 0.006
      case (_, _, Some(x), Some(y), _, _) =>
        x.keys.toSet == y.keys.toSet && x.keys.forall(k => closeJson(x(k).get, y(k).get))
      case (_, _, _, _, Some(x), Some(y)) => x.size == y.size && x.zip(y).forall(closeJson)
      case _                              => a == b
