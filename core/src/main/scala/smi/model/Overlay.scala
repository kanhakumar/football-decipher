package smi.model

import io.circe.{Codec, Json}
import smi.model.Check.*

final case class OverlayFocus(
    teamId: Option[TeamId] = None,
    playerId: Option[PlayerId] = None,
    metric: Option[MetricKey] = None
)
object OverlayFocus:
  given Codec.AsObject[OverlayFocus] = Wire.codec

/** A labelled number or string on a card. `value` is a JSON number or string. */
final case class OverlayMetric(label: String, value: Json, unit: Option[String] = None)
object OverlayMetric:
  given Codec.AsObject[OverlayMetric] = Wire.checked(Wire.codec[OverlayMetric]) { m =>
    that(
      m.value.isNumber || m.value.isString,
      s"metric '${m.label}' value must be number or string"
    )
  }

/** One player's average position on a shape map, in the team's own frame. */
final case class OverlayPosition(playerId: PlayerId, x: Double, y: Double)
object OverlayPosition:
  given Codec.AsObject[OverlayPosition] = Wire.checked(Wire.codec[OverlayPosition]) { p =>
    range("x", p.x, 0, 105) ++ range("y", p.y, 0, 68)
  }

final case class OverlayContent(
    headline: String,
    body: Option[String] = None,
    metrics: Option[List[OverlayMetric]] = None,
    playerId: Option[PlayerId] = None,
    teamId: Option[TeamId] = None,
    positions: Option[List[OverlayPosition]] = None,
    formation: Option[Formation] = None
)
object OverlayContent:
  given Codec.AsObject[OverlayContent] = Wire.checked(Wire.codec[OverlayContent]) { c =>
    range("headline.length", c.headline.length, 1, 120) ++
      opt(c.body)(b => range("body.length", b.length, 0, 400)) ++
      opt(c.metrics)(m => size("metrics", m.size, 0, 4)) ++
      opt(c.positions)(p => size("positions", p.size, 0, 11))
  }

/** Timed graphic scheduled on the viewer clock (video_ts_ms). `retract` removes an earlier one. */
final case class Overlay(
    overlayId: OverlayId,
    matchId: MatchId,
    insightId: Option[InsightId] = None,
    op: OverlayOp,
    `type`: Option[OverlayType] = None,
    tStartMs: Option[Long] = None,
    durationMs: Option[Int] = None,
    style: Option[OverlayStyle] = None,
    lang: Option[Lang] = None,
    persona: Option[Persona] = None,
    focus: Option[OverlayFocus] = None,
    content: Option[OverlayContent] = None,
    evidenceEventIds: Option[List[EventId]] = None,
    basis: Option[Basis] = None,
    importance: Option[Double] = None,
    verified: Option[Boolean] = None,
    templateFallback: Option[Boolean] = None,
    generatedAt: Option[Timestamp] = None
)

object Overlay:
  def errors(o: Overlay): List[String] =
    val required = List(
      "type" -> o.`type`,
      "t_start_ms" -> o.tStartMs,
      "duration_ms" -> o.durationMs,
      "style" -> o.style,
      "lang" -> o.lang,
      "persona" -> o.persona,
      "content" -> o.content,
      "importance" -> o.importance,
      "verified" -> o.verified,
      "template_fallback" -> o.templateFallback
    )
    opt(o.tStartMs)(t => atLeast("t_start_ms", t.toDouble, 0)) ++
      opt(o.durationMs)(d => range("duration_ms", d, 1000, 30000)) ++
      opt(o.importance)(range("importance", _, 0, 1)) ++
      (if o.op == OverlayOp.Show then
         required.collect { case (name, None) => s"show overlay requires $name" }
       else Nil)

  given Codec.AsObject[Overlay] = Wire.checked(Wire.codec[Overlay])(errors)
