package smi.raw

import io.circe.{Json, JsonObject}
import java.time.Instant
import smi.model.*

/** Something the adapter noticed: a value it repaired, or a message it could not use. */
final case class Issue(kind: String, ref: String, detail: String)

final case class AdapterConfig(
    registry: Registry = Registry.default,
    /** Events are held this long (in event time) so late arrivals can be put back in order. */
    reorderWindowMs: Long = 10_000
)

/** Adapter state between messages. Immutable: `Adapter.step` returns a new one. */
final case class AdapterState(
    seq: Long = 0,
    matchRef: Option[String] = None,
    kickoff: Option[Instant] = None,
    players: Map[Long, PlayerId] = Map.empty,
    periodEnds: Map[Int, Double] = Map.empty,
    /** Pending event upserts, by provider ID, not yet published. */
    buffer: Map[Long, RawMessage] = Map.empty,
    /** Current canonical version of every published event, by provider ID. */
    published: Map[Long, Event] = Map.empty,
    maxEventMs: Long = Long.MinValue,
    lastReleasedMs: Long = Long.MinValue
)

final case class AdapterOutput(
    envelopes: Vector[Envelope] = Vector.empty,
    issues: Vector[Issue] = Vector.empty
):
  def ++(o: AdapterOutput): AdapterOutput =
    AdapterOutput(envelopes ++ o.envelopes, issues ++ o.issues)

/** Raw provider feed -> canonical envelopes: rescaling, ID mapping, updates and deletes, and a
  * small reorder buffer for out-of-order delivery.
  */
final class Adapter(cfg: AdapterConfig = AdapterConfig()):
  private val registry = cfg.registry

  def run(messages: Seq[RawMessage]): (AdapterState, AdapterOutput) =
    val (state, out) = messages.foldLeft((AdapterState(), AdapterOutput())) { case ((s, acc), m) =>
      val (next, o) = step(s, m)
      (next, acc ++ o)
    }
    val (done, rest) = flush(state)
    (done, out ++ rest)

  def step(s: AdapterState, m: RawMessage): (AdapterState, AdapterOutput) =
    (m.kind, m.action) match
      case (RawKind.Match, _)                => onMatch(s, m)
      case (RawKind.Lineup, _)               => onLineup(s, m)
      case (RawKind.Event, RawAction.Upsert) => onUpsert(s, m)
      case (RawKind.Event, RawAction.Delete) => onDelete(s, m)

  /** Publishes everything still buffered (end of feed). */
  def flush(s: AdapterState): (AdapterState, AdapterOutput) = release(s, Long.MaxValue)

  // ---- match and lineups ----

  private def onMatch(s: AdapterState, m: RawMessage): (AdapterState, AdapterOutput) =
    val info = m.matchInfo.get
    toMatchMeta(m.matchRef, info) match
      case Left(err) => (s, AdapterOutput(issues = Vector(Issue("rejected", "match", err))))
      case Right(meta) =>
        val op = if s.matchRef.isEmpty then Op.Create else Op.Update
        val next = s.copy(matchRef = Some(m.matchRef), kickoff = Some(info.kickoff.toInstant))
        envelope(next, op, m.lastModified, PayloadType.MatchMeta, Payload.Meta(meta))

  private def toMatchMeta(ref: String, i: RawMatchInfo): Either[String, MatchMeta] =
    def team(id: Int) = registry.teamById.get(id).toRight(s"unknown provider team $id")
    for
      matchId <- MatchId.from(ref)
      home <- team(i.homeTeamId)
      away <- team(i.awayTeamId)
      status <- registry.statusById.get(i.statusId).toRight(s"unknown status ${i.statusId}")
      score <- i.score.toList
        .map((k, v) => k.toIntOption.toRight(s"bad score key $k").flatMap(team).map(_.value -> v))
        .partitionMap(identity) match
        case (Nil, pairs)  => Right(scala.collection.immutable.ListMap.from(pairs))
        case (err :: _, _) => Left(err)
    yield MatchMeta(
      matchId = matchId,
      competition = i.competition,
      season = i.season,
      kickoffUtc = i.kickoff,
      status = status,
      homeTeamId = home,
      awayTeamId = away,
      pitch = PitchSize(i.pitchLengthM, i.pitchWidthM),
      score = score,
      dataOrigin = registry.dataOrigin
    )

  private def onLineup(s: AdapterState, m: RawMessage): (AdapterState, AdapterOutput) =
    val raw = m.lineup.get
    val built = for
      teamId <- registry.teamById.get(raw.teamId).toRight(s"unknown provider team ${raw.teamId}")
      formation <- Formation.from(raw.formation)
      colors <- raw.colors.map(_.map(HexColor.from).partitionMap(identity)) match
        case Some((Nil, cs))     => Right(Some(cs))
        case Some((err :: _, _)) => Left(err)
        case None                => Right(None)
      players <- raw.players
        .map { p =>
          Position
            .fromWire(p.position)
            .map(pos =>
              p -> Player(Mapping.playerId(teamId, p.shirt), p.name, p.shirt, pos, p.slot)
            )
        }
        .partitionMap(identity) match
        case (Nil, ps)     => Right(ps)
        case (err :: _, _) => Left(err)
    yield (
      Lineup(
        teamId,
        raw.name,
        raw.short,
        colors,
        formation,
        players.collect { case (r, p) if r.starter => p },
        players.collect { case (r, p) if !r.starter => p }
      ),
      players.map((r, p) => r.id -> p.playerId)
    )
    built match
      case Left(err) =>
        (s, AdapterOutput(issues = Vector(Issue("rejected", s"lineup ${raw.teamId}", err))))
      case Right((lineup, ids)) =>
        val next = s.copy(players = s.players ++ ids)
        envelope(next, Op.Create, m.lastModified, PayloadType.Lineup, Payload.Team(lineup))

  // ---- events ----

  private def eventMs(m: RawMessage): Long = m.event.get.timestamp.toInstant.toEpochMilli

  private def onUpsert(s: AdapterState, m: RawMessage): (AdapterState, AdapterOutput) =
    val id = m.event.get.id
    val ts = eventMs(m)
    if s.published.contains(id) then publish(s, m, Op.Update)
    else if ts < s.lastReleasedMs then
      val (next, out) = publish(s, m, Op.Create)
      (
        next,
        out.copy(issues =
          Issue("late", s"event $id", "arrived after later events were published") +: out.issues
        )
      )
    else
      val buffered =
        s.copy(buffer = s.buffer.updated(id, m), maxEventMs = math.max(s.maxEventMs, ts))
      release(buffered, buffered.maxEventMs - cfg.reorderWindowMs)

  private def onDelete(s: AdapterState, m: RawMessage): (AdapterState, AdapterOutput) =
    val id = m.eventId.get
    if s.buffer.contains(id) then (s.copy(buffer = s.buffer - id), AdapterOutput())
    else if s.published.contains(id) then
      val next = s.copy(published = s.published - id)
      envelope(
        next,
        Op.Delete,
        m.lastModified,
        PayloadType.Event,
        Payload.Delete(DeleteRef(Mapping.eventId(id)))
      )
    else
      (
        s,
        AdapterOutput(issues =
          Vector(Issue("ignored", s"event $id", "delete for an unknown event"))
        )
      )

  /** Publishes buffered events up to the watermark, in event-time order. */
  private def release(s: AdapterState, watermarkMs: Long): (AdapterState, AdapterOutput) =
    val ready = s.buffer.values.toVector
      .filter(eventMs(_) <= watermarkMs)
      .sortBy(m => (eventMs(m), m.event.get.id))
    ready.foldLeft((s.copy(buffer = s.buffer -- ready.map(_.event.get.id)), AdapterOutput())) {
      case ((st, acc), m) =>
        val (next, out) = publish(st, m, Op.Create)
        (next.copy(lastReleasedMs = math.max(next.lastReleasedMs, eventMs(m))), acc ++ out)
    }

  private def publish(s: AdapterState, m: RawMessage, op: Op): (AdapterState, AdapterOutput) =
    val raw = m.event.get
    toEvent(s, raw) match
      case Left(err) =>
        (s, AdapterOutput(issues = Vector(Issue("rejected", s"event ${raw.id}", err))))
      case Right((event, repairs)) =>
        val ended =
          if event.`type` == EventType.PeriodEnd then
            s.periodEnds.updated(event.period, event.periodClockS)
          else s.periodEnds
        val next = s.copy(published = s.published.updated(raw.id, event), periodEnds = ended)
        val (done, out) = envelope(next, op, m.lastModified, PayloadType.Event, Payload.Ev(event))
        (done, out.copy(issues = repairs ++ out.issues))

  private def envelope(s: AdapterState, op: Op, at: Timestamp, pt: PayloadType, p: Payload) =
    val env = Envelope(
      Envelope.SchemaVersion,
      s.seq,
      MatchId.unsafe(s.matchRef.getOrElse("UNKNOWN")),
      Source.ProviderAdapter,
      op,
      at,
      pt,
      p
    )
    (s.copy(seq = s.seq + 1), AdapterOutput(envelopes = Vector(env)))

  // ---- raw event -> canonical event ----

  private def ids(s: AdapterState): Mapping.Ids = Mapping.Ids(
    registry.teamIdOf.get,
    registry.teamById.get,
    s.players.map(_.swap).get,
    s.players.get
  )

  /** A coordinate in 0..100, clamped with an issue if just outside, rejected if far outside. */
  private def coordinate(
      v: Double,
      axis: String,
      ref: String
  ): Either[String, (Double, Option[Issue])] =
    if v >= 0 && v <= 100 then Right((v, None))
    else if v >= -Mapping.CoordinateSlack && v <= 100 + Mapping.CoordinateSlack then
      val c = math.max(0.0, math.min(100.0, v))
      Right((c, Some(Issue("repaired", ref, s"$axis=$v clamped to $c"))))
    else Left(s"$axis=$v is outside the pitch")

  private def toEvent(s: AdapterState, raw: RawEvent): Either[String, (Event, Vector[Issue])] =
    val ref = s"event ${raw.id}"
    val known = ids(s)
    def axis(v: Option[Double], name: String, scale: Double => Double) =
      v match
        case Some(x) => coordinate(x, name, ref).map((c, issue) => (Some(scale(c)), issue))
        case None    => Right((None, None))
    for
      kickoff <- s.kickoff.toRight("no match info received yet")
      eventType <- registry.typeById.get(raw.typeId).toRight(s"unknown type ID ${raw.typeId}")
      team <- registry.teamById.get(raw.teamId).toRight(s"unknown provider team ${raw.teamId}")
      player <- raw.playerId match
        case Some(p) => s.players.get(p).toRight(s"unknown provider player $p").map(Some(_))
        case None    => Right(None)
      periodClock <- Mapping.fromRawClock(raw.periodId, raw.min, raw.sec)
      (x, xIssue) <- axis(raw.x, "x", Mapping.fromRawX)
      (y, yIssue) <- axis(raw.y, "y", Mapping.fromRawY)
      named <- qualifiers(raw, known)
      (outcome, outcomeIssue) <- outcomeOf(eventType, raw, named, ref)
      details <- detailsOf(eventType, named)
      level <- eventLevel(named)
    yield
      val elapsedBefore =
        (1 until raw.periodId).map(p => s.periodEnds.getOrElse(p, Mapping.nominalLengthS(p))).sum
      val wallMs = raw.timestamp.toInstant.toEpochMilli - kickoff.toEpochMilli
      val event = Event(
        eventId = Mapping.eventId(raw.id),
        `type` = eventType,
        period = raw.periodId,
        periodClockS = periodClock,
        matchClockS = elapsedBefore + periodClock,
        wallclockUtc = raw.timestamp,
        videoTsMs = wallMs + registry.videoOffsetMs,
        teamId = team,
        playerId = player,
        x = x,
        y = y,
        possessionId = level.possessionId,
        sequenceIndex = level.sequenceIndex,
        underPressure = level.underPressure,
        outcome = outcome,
        relatedEventIds = level.relatedEventIds,
        details = details,
        sourceIds = Some(
          SourceIds(
            Some(registry.provider),
            Some(raw.id.toString),
            raw.playerId.map(_.toString),
            Some(raw.teamId.toString)
          )
        )
      )
      (event, Vector(xIssue, yIssue, outcomeIssue).flatten)
    end for
  end toEvent

  /** Qualifiers as canonical JSON values, keyed by name. Unknown qualifier IDs are rejected. */
  private def qualifiers(
      raw: RawEvent,
      known: Mapping.Ids
  ): Either[String, Vector[(String, Json)]] =
    raw.qualifiers.toVector
      .map { q =>
        for
          spec <- registry.qualifierById.get(q.qid).toRight(s"unknown qualifier ID ${q.qid}")
          value <- Mapping
            .canonicalValue(spec.kind, q.value, known)
            .left
            .map(e => s"${spec.name}: $e")
        yield spec.name -> value
      }
      .partitionMap(identity) match
      case (Vector(), named) => Right(named)
      case (errs, _)         => Left(errs.head)

  private def outcomeOf(t: EventType, raw: RawEvent, named: Vector[(String, Json)], ref: String) =
    val result = named.collectFirst { case ("result", j) => j.asString }.flatten
    raw.outcome match
      case None => Right((None, None))
      case Some(bit) =>
        result match
          case Some(r) => Outcome.fromWire(r).map(o => (Some(o), None))
          case None =>
            Mapping.impliedOutcome(t, bit) match
              case Some(o) => Right((Some(o), None))
              case None =>
                val o = Mapping.defaultOutcome(t)
                Right(
                  (
                    Some(o),
                    Some(
                      Issue(
                        "repaired",
                        ref,
                        s"missing result qualifier, assumed ${Outcome.wire(o)}"
                      )
                    )
                  )
                )

  private def detailsOf(
      t: EventType,
      named: Vector[(String, Json)]
  ): Either[String, Option[EventDetails]] =
    val fields = named.filterNot((n, _) => Mapping.EventLevel(n))
    if fields.isEmpty then Right(None)
    else EventDetails.decodeFor(t, JsonObject.fromIterable(fields)).map(Some(_))

  private final case class Level(
      possessionId: Option[PossessionId],
      sequenceIndex: Option[Int],
      underPressure: Option[Boolean],
      relatedEventIds: Option[List[EventId]]
  )

  private def eventLevel(named: Vector[(String, Json)]): Either[String, Level] =
    val m = named.toMap
    for
      possession <- m.get("possession_id").flatMap(_.asString) match
        case Some(p) => PossessionId.from(p).map(Some(_))
        case None    => Right(None)
      related <- m.get("related_event_ids") match
        case Some(j) => j.as[List[EventId]].map(Some(_)).left.map(_.getMessage)
        case None    => Right(None)
    yield Level(
      possession,
      m.get("sequence_index").flatMap(_.asNumber).flatMap(_.toInt),
      m.get("under_pressure").flatMap(_.asBoolean),
      related
    )
