package smi.raw

import io.circe.syntax.*
import scala.collection.immutable.ListMap
import smi.model.*

/** Canonical feed -> provider-style raw feed. Tracking frames are not part of the raw feed. */
final class RawEmitter(registry: Registry = Registry.default):
  /** Converts a canonical feed in order. Lineups must precede the events that use their players. */
  def emit(envelopes: Vector[Envelope]): Either[String, Vector[RawMessage]] =
    val players = envelopes
      .collect { case Envelope(_, _, _, _, _, _, _, Payload.Team(l)) => l }
      .flatMap(lineupIds)
      .toMap
    val ids = idsWith(players)
    envelopes.foldLeft(Right(Vector.empty): Either[String, Vector[RawMessage]]) { (acc, env) =>
      acc.flatMap(out => message(env, ids).map(m => out ++ m))
    }

  private def lineupIds(l: Lineup): List[(PlayerId, Long)] =
    val team =
      registry.teamIdOf.getOrElse(l.teamId, sys.error(s"no provider ID for ${l.teamId.value}"))
    (l.starters ++ l.bench).map(p => p.playerId -> Mapping.rawPlayerId(team, p.shirt))

  private def idsWith(players: Map[PlayerId, Long]): Mapping.Ids =
    Mapping.Ids(
      registry.teamIdOf.get,
      registry.teamById.get,
      players.get,
      players.map(_.swap).get
    )

  private def message(env: Envelope, ids: Mapping.Ids): Either[String, Option[RawMessage]] =
    def wrap(kind: RawKind, action: RawAction) =
      RawMessage(kind, action, env.matchId.value, env.emittedAt)
    env.payload match
      case Payload.Meta(m) =>
        matchInfo(m).map(i => Some(wrap(RawKind.Match, RawAction.Upsert).copy(matchInfo = Some(i))))
      case Payload.Team(l) =>
        lineup(l).map(r => Some(wrap(RawKind.Lineup, RawAction.Upsert).copy(lineup = Some(r))))
      case Payload.Ev(e) =>
        event(e, ids).map(r => Some(wrap(RawKind.Event, RawAction.Upsert).copy(event = Some(r))))
      case Payload.Delete(d) =>
        Right(
          Some(
            wrap(RawKind.Event, RawAction.Delete)
              .copy(eventId = Some(Mapping.rawEventId(d.eventId)))
          )
        )
      case Payload.Frame(_) | Payload.Raw(_) => Right(None)

  private def team(t: TeamId): Either[String, Int] =
    registry.teamIdOf.get(t).toRight(s"no provider ID for ${t.value}")

  private def matchInfo(m: MatchMeta): Either[String, RawMatchInfo] =
    for
      home <- team(m.homeTeamId)
      away <- team(m.awayTeamId)
      status <- registry.statusIdOf
        .get(m.status)
        .toRight(s"no status code for ${MatchStatus.wire(m.status)}")
      score <- m.score.toList
        .map((k, v) => TeamId.from(k).flatMap(team).map(_.toString -> v))
        .partitionMap(identity) match
        case (Nil, pairs)  => Right(ListMap.from(pairs))
        case (err :: _, _) => Left(err)
    yield RawMatchInfo(
      m.competition,
      m.season,
      m.kickoffUtc,
      status,
      home,
      away,
      m.pitch.lengthM,
      m.pitch.widthM,
      score
    )

  private def lineup(l: Lineup): Either[String, RawLineup] =
    team(l.teamId).map { t =>
      def raw(p: Player, starter: Boolean) =
        RawPlayer(
          Mapping.rawPlayerId(t, p.shirt),
          p.name,
          p.shirt,
          Position.wire(p.position),
          starter,
          p.slot
        )
      RawLineup(
        t,
        l.name,
        l.short,
        l.colors.map(_.map(_.value)),
        l.formation.value,
        l.starters.map(raw(_, true)) ++ l.bench.map(raw(_, false))
      )
    }

  def event(e: Event, ids: Mapping.Ids): Either[String, RawEvent] =
    val (min, sec) = Mapping.toRawClock(e.period, e.periodClockS)
    for
      typeId <- registry.typeIdOf
        .get(e.`type`)
        .toRight(s"no type ID for ${EventType.wire(e.`type`)}")
      teamRaw <- team(e.teamId)
      player <- e.playerId match
        case Some(p) => ids.playerToRaw(p).toRight(s"no provider ID for ${p.value}").map(Some(_))
        case None    => Right(None)
      qualifiers <- qualifiers(e, ids)
    yield RawEvent(
      id = Mapping.rawEventId(e.eventId),
      typeId = typeId,
      periodId = e.period,
      min = min,
      sec = sec,
      timestamp = e.wallclockUtc,
      teamId = teamRaw,
      playerId = player,
      x = e.x.map(Mapping.toRawX),
      y = e.y.map(Mapping.toRawY),
      outcome = e.outcome.map(Mapping.outcomeBit),
      qualifiers = qualifiers
    )

  private def qualifiers(e: Event, ids: Mapping.Ids): Either[String, List[RawQualifier]] =
    val result = e.outcome.collect {
      case o if !Mapping.impliedOutcome(e.`type`, Mapping.outcomeBit(o)).contains(o) =>
        "result" -> Outcome.wire(o).asJson
    }
    val eventLevel = List(
      result,
      e.possessionId.map(p => "possession_id" -> p.value.asJson),
      e.sequenceIndex.map(i => "sequence_index" -> i.asJson),
      e.underPressure.map(b => "under_pressure" -> b.asJson),
      e.relatedEventIds.map(r => "related_event_ids" -> r.map(_.value).asJson)
    ).flatten
    val details = e.details.toList.flatMap(d => d.asJson.asObject.toList.flatMap(_.toList))
    (eventLevel ++ details)
      .map { (name, json) =>
        for
          qid <- registry.qualifierIdOf
            .get(name)
            .toRight(s"${e.eventId.value}: no qualifier for '$name'")
          kind = registry.qualifierById(qid).kind
          value <- Mapping
            .qualifierValue(kind, json, ids)
            .left
            .map(err => s"${e.eventId.value} $name: $err")
        yield RawQualifier(qid, value)
      }
      .partitionMap(identity) match
      case (Nil, qs)     => Right(qs.sortBy(_.qid))
      case (err :: _, _) => Left(err)
