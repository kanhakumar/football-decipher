package smi.api

import io.circe.Codec
import scala.collection.immutable.ListMap
import smi.engine.{Engine, MetricValue}
import smi.model.*

// Request and response bodies of the core HTTP API (ADR-013). Field names are snake_case.

final case class StartRequest(
    seed: Option[Long] = None,
    speed: Option[Double] = None,
    delayS: Option[Double] = None,
    tracking: Option[Boolean] = None
)
object StartRequest:
  given Codec.AsObject[StartRequest] = Wire.codec

final case class SpeedRequest(speed: Double)
object SpeedRequest:
  given Codec.AsObject[SpeedRequest] = Wire.codec

final case class ClockView(period: Int, minute: Int, second: Int, matchClockS: Double)
object ClockView:
  given Codec.AsObject[ClockView] = Wire.codec
  def of(e: Event): ClockView =
    val whole = e.periodClockS.toInt
    ClockView(e.period, Engine.baseMinute(e.period) + whole / 60, whole % 60, e.matchClockS)

final case class MatchStateView(
    status: String,
    seed: Option[Long],
    speed: Double,
    delayS: Double,
    tracking: Boolean,
    live: Option[ClockView],
    viewer: Option[ClockView],
    score: ListMap[String, Int],
    possessionTeam: Option[TeamId],
    playersOnPitch: ListMap[String, Int],
    envelopesTotal: Int,
    envelopesLive: Int,
    envelopesViewer: Int,
    insights: Int,
    overlays: Int
)
object MatchStateView:
  import Wire.given
  given Codec.AsObject[MatchStateView] = Wire.codec

final case class MetricView(
    metric: String,
    value: Option[Double],
    unit: String,
    basis: Basis,
    capabilityLevel: Int,
    via: Option[String] = None
)
object MetricView:
  given Codec.AsObject[MetricView] = Wire.codec
  def of(v: MetricValue): MetricView =
    MetricView(
      v.metric,
      v.value.map(x => StrictMath.round(x * 1000) / 1000.0),
      v.unit,
      v.basis,
      v.capabilityLevel,
      v.via
    )

final case class WindowView(
    period: Option[Int],
    fromMinute: Option[Double],
    toMinute: Option[Double]
)
object WindowView:
  given Codec.AsObject[WindowView] = Wire.codec

final case class StatsView(subject: String, window: WindowView, metrics: List[MetricView])
object StatsView:
  given Codec.AsObject[StatsView] = Wire.codec

final case class PositionView(
    playerId: PlayerId,
    name: Option[String],
    x: Double,
    y: Double,
    samples: Int
)
object PositionView:
  given Codec.AsObject[PositionView] = Wire.codec

final case class ShapeView(
    teamId: TeamId,
    window: WindowView,
    basis: Basis,
    players: List[PositionView]
)
object ShapeView:
  given Codec.AsObject[ShapeView] = Wire.codec

/** One event, compact: what an agent needs to refer to it, never the whole payload. */
final case class EventView(
    eventId: EventId,
    minute: Int,
    second: Int,
    period: Int,
    `type`: EventType,
    teamId: TeamId,
    playerId: Option[PlayerId],
    outcome: Option[Outcome],
    x: Option[Double],
    y: Option[Double]
)
object EventView:
  given Codec.AsObject[EventView] = Wire.codec
  def of(e: Event): EventView =
    val c = ClockView.of(e)
    EventView(
      e.eventId,
      c.minute,
      c.second,
      e.period,
      e.`type`,
      e.teamId,
      e.playerId,
      e.outcome,
      e.x,
      e.y
    )

final case class EventsView(count: Int, events: List[EventView])
object EventsView:
  given Codec.AsObject[EventsView] = Wire.codec

final case class EvidenceSummary(count: Int, byType: ListMap[String, Int], sample: List[EventView])
object EvidenceSummary:
  import Wire.given
  given Codec.AsObject[EvidenceSummary] = Wire.codec

final case class InsightDetail(insight: Insight, evidence: EvidenceSummary)
object InsightDetail:
  given Codec.AsObject[InsightDetail] = Wire.codec

final case class InsightsView(count: Int, insights: List[Insight])
object InsightsView:
  given Codec.AsObject[InsightsView] = Wire.codec

final case class ErrorView(error: String)
object ErrorView:
  given Codec.AsObject[ErrorView] = Wire.codec
