package smi.insight

import io.circe.{Json, JsonObject}
import smi.engine.{BucketKey, Engine, EngineState}
import smi.model.*

/** An active insight and how to re-check it after a correction. */
final case class Published(insight: Insight, detector: Detector, candidate: Candidate)

final case class MomentState(
    engine: EngineState = Engine.empty,
    active: Vector[Published] = Vector.empty,
    /** Every insight version emitted, in order: new ones, retractions and replacements. */
    out: Vector[Insight] = Vector.empty,
    nextId: Int = 1,
    lastBucket: Option[BucketKey] = None,
    cooldowns: Map[String, Int] = Map.empty
):
  /** Insights still standing, best first. */
  def ranked: Vector[Insight] = active.map(_.insight).sortBy(i => (-i.importance, i.tMatchS))

/** Runs the detectors over a feed as it arrives (ADR-011). Pure: the same feed gives the same
  * insights.
  */
object Moments:
  val CooldownMinutes = 10

  def run(envelopes: Iterable[Envelope]): MomentState = finish(
    envelopes.foldLeft(MomentState())(step)
  )

  /** Closes the last minute of the feed. */
  def finish(s: MomentState): MomentState =
    s.lastBucket.fold(s)(b => windowDetectors(s, b.period, b.displayMinute + 1))

  def step(s: MomentState, env: Envelope): MomentState =
    val known = env.payload match
      case Payload.Ev(e) => s.engine.events.contains(e.eventId)
      case _             => false
    val next = s.copy(engine = Engine.apply(s.engine, env))
    env.payload match
      case Payload.Ev(e) if !known =>
        eventDetectors(minutesCompleted(next, BucketKey.of(e)), e)
      case Payload.Ev(e)     => eventDetectors(corrections(next, e.eventId, env.emittedAt), e)
      case Payload.Delete(d) => corrections(next, d.eventId, env.emittedAt)
      case _                 => next

  // ---- facts and publishing ----

  /** Window bounds first (in the detector's order), then everything `derive` computes. */
  def factsFor(d: Detector, h: Header, evidence: Vector[Event]): JsonObject =
    val windows = d.windowKeys.map(k => k -> Json.fromDoubleOrNull(h.windows(k)))
    JsonObject.fromIterable(windows ++ d.derive(h, evidence).toList)

  private def build(
      s: MomentState,
      d: Detector,
      c: Candidate,
      at: Timestamp,
      supersedes: Option[InsightId]
  ): Insight =
    val evidence = c.evidence.map(s.engine.events(_).event)
    val facts = factsFor(d, c.header, evidence)
    val insight = Insight(
      insightId = InsightId.unsafe(f"I_${s.nextId}%04d"),
      matchId = s.engine.meta.map(_.matchId).getOrElse(MatchId.unsafe("UNKNOWN")),
      kind = d.kind,
      period = c.header.period,
      tMatchS = c.trigger.matchClockS,
      videoTsMs = c.trigger.videoTsMs,
      teams = c.header.teams,
      players = Option.when(c.header.players.nonEmpty)(c.header.players),
      facts = facts,
      evidenceEventIds = c.evidence.toList,
      whyItMatters = d.why(facts),
      importance = d.importance(facts),
      basis = Basis.EventData,
      capabilityLevel = 0,
      createdAt = at,
      status = Some(InsightStatus.Active),
      supersedes = supersedes
    )
    val errors = Insight.errors(insight)
    if errors.nonEmpty then
      throw IllegalStateException(s"${d.kind} built an invalid insight: $errors")
    insight

  private def publish(
      s: MomentState,
      d: Detector,
      c: Candidate,
      at: Timestamp,
      supersedes: Option[InsightId] = None
  ) =
    val insight = build(s, d, c, at, supersedes)
    s.copy(
      active = s.active :+ Published(insight, d, c),
      out = s.out :+ insight,
      nextId = s.nextId + 1
    )

  private def triggeredBy(s: MomentState, d: Detector, e: Event): Boolean =
    s.active.exists(p => p.detector == d && p.candidate.trigger.eventId == e.eventId)

  private def eventDetectors(s: MomentState, e: Event): MomentState =
    s.engine.events.get(e.eventId).fold(s) { stored =>
      Detectors.all.foldLeft(s) { (acc, d) =>
        if triggeredBy(acc, d, stored.event) then acc
        else
          d.onEvent(acc.engine, stored.event)
            .fold(acc)(c => publish(acc, d, c, stored.event.wallclockUtc))
      }
    }

  // ---- window detectors, run as each minute completes ----

  private def minutesCompleted(s: MomentState, next: BucketKey): MomentState =
    s.lastBucket match
      case None => s.copy(lastBucket = Some(next))
      case Some(last) if next.period == last.period && next.minute <= last.minute => s
      case Some(last) if next.period < last.period                                => s
      case Some(last) =>
        val endMinute = if next.period == last.period then next.minute - 1 else last.minute
        val done = (last.minute to endMinute).foldLeft(s) { (acc, m) =>
          windowDetectors(acc, last.period, BucketKey(last.period, m).displayMinute + 1)
        }
        done.copy(lastBucket = Some(next))

  private def windowDetectors(s: MomentState, period: Int, minute: Int): MomentState =
    Detectors.all.foldLeft(s) { (acc, d) =>
      d.onMinute(acc.engine, period, minute).foldLeft(acc) { (a, c) =>
        val key = c.cooldownKey.getOrElse(d.kind.toString)
        val cooling = a.cooldowns.get(key).exists(last => minute - last < CooldownMinutes)
        if cooling then a
        else
          publish(a, d, c, c.trigger.wallclockUtc)
            .copy(cooldowns = a.cooldowns.updated(key, minute))
      }
    }

  // ---- corrections ----

  /** Re-checks every active insight that used event `id` as evidence or as its trigger. */
  private def corrections(s: MomentState, id: EventId, at: Timestamp): MomentState =
    val affected = s.active.filter(p =>
      p.insight.evidenceEventIds.contains(id) || p.candidate.trigger.eventId == id
    )
    affected.foldLeft(s) { (acc, p) =>
      val rest = acc.active.filterNot(_ == p)
      def retracted = p.insight.copy(status = Some(InsightStatus.Retracted), createdAt = at)
      p.candidate.recheck(acc.engine) match
        case None => acc.copy(active = rest, out = acc.out :+ retracted)
        case Some(c) =>
          val facts = factsFor(p.detector, c.header, c.evidence.map(acc.engine.events(_).event))
          if facts == p.insight.facts && c.evidence.toList == p.insight.evidenceEventIds then
            acc.copy(active = rest :+ p.copy(candidate = c))
          else
            publish(
              acc.copy(active = rest, out = acc.out :+ retracted),
              p.detector,
              c,
              at,
              Some(p.insight.insightId)
            )
    }
