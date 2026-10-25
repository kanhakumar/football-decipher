package smi.api

import smi.engine.{EngineState, Window}
import smi.model.*

/** Overlays the core publishes itself: factual, template-only, no model output (D4). Their IDs
  * start at O_90000000 so they never collide with the agent service's IDs.
  */
object Overlays:
  val CoreIdBase = 90_000_000

  def scoreline(i: Insight, n: Int, headline: String): Overlay =
    Overlay(
      overlayId = OverlayId.unsafe(s"O_${CoreIdBase + n}"),
      matchId = i.matchId,
      insightId = Some(i.insightId),
      op = OverlayOp.Show,
      `type` = Some(OverlayType.ScorelineUpdate),
      tStartMs = Some(i.videoTsMs),
      durationMs = Some(8_000),
      style = Some(OverlayStyle.CornerCard),
      lang = Some(Lang.En),
      persona = Some(Persona.Casual),
      focus = i.teams.headOption.map(t => OverlayFocus(teamId = Some(t))),
      content = Some(OverlayContent(headline = headline, teamId = i.teams.headOption)),
      evidenceEventIds = Some(i.evidenceEventIds.takeRight(1)),
      basis = Some(i.basis),
      importance = Some(i.importance),
      verified = Some(true),
      templateFallback = Some(false),
      generatedAt = Some(i.createdAt)
    )

  def retract(id: OverlayId, i: Insight): Overlay =
    Overlay(
      overlayId = id,
      matchId = i.matchId,
      insightId = Some(i.insightId),
      op = OverlayOp.Retract,
      generatedAt = Some(i.createdAt)
    )

  /** The shape map for a tactical shift: each player's average action position over the spell
    * (event-based, so basis `event_estimate`), the eleven busiest players at most.
    */
  def shape(i: Insight, n: Int, engine: EngineState): Option[Overlay] =
    def fact(k: String) = i.facts(k).flatMap(_.asNumber).map(_.toDouble)
    for
      team <- i.teams.headOption
      from <- fact("before_to_minute")
      to <- fact("after_to_minute")
      lineup <- engine.lineups.get(team)
      w = Window(Some(i.period), from, to)
      byPlayer = engine.ordered
        .map(_.event)
        .filter(e => e.teamId == team && w.contains(e) && e.x.isDefined && e.y.isDefined)
        .flatMap(e => e.playerId.map(_ -> e))
        .groupMap(_._1)(_._2)
      if byPlayer.nonEmpty
    yield
      def avg(xs: Vector[Double]) = math.round(xs.sum / xs.size * 10) / 10.0
      val positions = byPlayer.toList
        .sortBy((p, es) => (-es.size, p.value))
        .take(11)
        .map((p, es) => OverlayPosition(p, avg(es.flatMap(_.x)), avg(es.flatMap(_.y))))
        .sortBy(_.playerId.value)
      val fullTeam = i.facts("ctx_players_for").flatMap(_.asNumber).flatMap(_.toInt).contains(11)
      Overlay(
        overlayId = OverlayId.unsafe(s"O_${CoreIdBase + n}"),
        matchId = i.matchId,
        insightId = Some(i.insightId),
        op = OverlayOp.Show,
        `type` = Some(OverlayType.ShapeMap),
        tStartMs = Some(i.videoTsMs),
        durationMs = Some(10_000),
        style = Some(OverlayStyle.PitchOverlay),
        lang = Some(Lang.En),
        persona = Some(Persona.Casual),
        focus = Some(OverlayFocus(teamId = Some(team))),
        content = Some(
          OverlayContent(
            headline = f"${lineup.name} average positions, minutes ${from.toInt} to ${to.toInt}",
            teamId = Some(team),
            positions = Some(positions),
            formation = Option.when(fullTeam)(lineup.formation)
          )
        ),
        basis = Some(Basis.EventEstimate),
        importance = Some(i.importance),
        verified = Some(true),
        templateFallback = Some(false),
        generatedAt = Some(i.createdAt)
      )
