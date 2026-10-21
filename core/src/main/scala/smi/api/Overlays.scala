package smi.api

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
