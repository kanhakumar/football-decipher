package smi.engine

import smi.model.*

/** Whose numbers a ratio part uses, relative to the team asked about. */
enum Of:
  case Own, Opponent, Both

final case class Ref(metric: String, of: Of = Of.Own)

/** How a metric is computed from enriched events. */
sealed trait Calc
object Calc:
  /** Number of events matching the predicate, credited to the acting team and player. */
  final case class Count(pred: Enriched => Boolean) extends Calc

  /** Sum of a value over matching events, credited like `Count`. */
  final case class Sum(pred: Enriched => Boolean, value: Enriched => Double) extends Calc

  /** Computed at query time from window sums; undefined when the denominator is zero. */
  final case class Ratio(num: Ref, den: Ref, scale: Double = 1.0) extends Calc

  /** Needs tracking data the engine does not have yet; always answered by the fallback. */
  case object Tracking extends Calc

/** A metric declares what data it requires (capability level), its fallback when that data is
  * missing, and the basis of its output (CLAUDE.md, ADR-010).
  */
final case class MetricDef(
    id: String,
    unit: String,
    calc: Calc,
    requires: Int = 0,
    fallback: Option[String] = None,
    basis: Basis = Basis.EventData,
    teamOnly: Boolean = false
)

object Metrics:
  import Calc.*

  private def shot(e: Enriched) = e.is(EventType.Shot)
  private def pass(e: Enriched) = e.is(EventType.Pass)
  private def outcome(e: Enriched, o: Outcome) = e.event.outcome.contains(o)
  private def card(e: Enriched, c: Card) = e.event.details.exists {
    case f: FoulDetails => f.card == c
    case _              => false
  }
  private def x(e: Enriched) = e.event.x.getOrElse(0.0)
  private def defensiveAction(e: Enriched) =
    e.is(EventType.Tackle) || e.is(EventType.Interception) || e.is(EventType.Foul)

  /** The opponent's own 60% of the pitch starts at x = 42 in the pressing team's frame. */
  val PressingLine = 42.0

  val all: Vector[MetricDef] = Vector(
    MetricDef("involvements", "events", Count(_.event.playerId.isDefined)),
    MetricDef("goals", "goals", Count(e => shot(e) && outcome(e, Outcome.Goal))),
    MetricDef("shots", "shots", Count(shot)),
    MetricDef(
      "shots_on_target",
      "shots",
      Count(e => shot(e) && (outcome(e, Outcome.Goal) || outcome(e, Outcome.Saved)))
    ),
    MetricDef("xg", "xG", Sum(shot, _.xg.getOrElse(0.0))),
    MetricDef("xg_per_shot", "xG", Ratio(Ref("xg"), Ref("shots"))),
    MetricDef("passes", "passes", Count(pass)),
    MetricDef("passes_completed", "passes", Count(e => pass(e) && e.completed)),
    MetricDef("pass_completion", "%", Ratio(Ref("passes_completed"), Ref("passes"), 100)),
    MetricDef("expected_completions", "passes", Sum(pass, _.expectedCompletion.getOrElse(0.0))),
    MetricDef(
      "completion_vs_expected",
      "passes",
      Sum(pass, e => (if e.completed then 1.0 else 0.0) - e.expectedCompletion.getOrElse(0.0))
    ),
    MetricDef(
      "completion_above_expected",
      "%",
      Ratio(Ref("completion_vs_expected"), Ref("passes"), 100)
    ),
    MetricDef("progressive_passes", "passes", Count(_.progressive)),
    MetricDef("final_third_entries", "passes", Count(_.finalThirdEntry)),
    MetricDef("box_entries", "passes", Count(_.boxEntry)),
    MetricDef(
      "final_third_passes",
      "passes",
      Count(e => pass(e) && x(e) >= Zones.AttackingThirdStart)
    ),
    MetricDef("pressures", "actions", Count(_.is(EventType.Pressure))),
    MetricDef(
      "tackles_won",
      "tackles",
      Count(e => e.is(EventType.Tackle) && outcome(e, Outcome.Won))
    ),
    MetricDef("interceptions", "actions", Count(_.is(EventType.Interception))),
    MetricDef("fouls", "fouls", Count(_.is(EventType.Foul))),
    MetricDef("yellow_cards", "cards", Count(card(_, Card.Yellow))),
    MetricDef("red_cards", "cards", Count(card(_, Card.Red))),
    MetricDef(
      "defensive_actions_high",
      "actions",
      Count(e => defensiveAction(e) && x(e) >= PressingLine)
    ),
    MetricDef("passes_own_60", "passes", Count(e => pass(e) && x(e) < 105 - PressingLine)),
    MetricDef(
      "possession_share",
      "%",
      Ratio(Ref("passes"), Ref("passes", Of.Both), 100),
      teamOnly = true
    ),
    MetricDef(
      "field_tilt",
      "%",
      Ratio(Ref("final_third_passes"), Ref("final_third_passes", Of.Both), 100),
      teamOnly = true
    ),
    MetricDef(
      "ppda",
      "passes per action",
      Ratio(Ref("passes_own_60", Of.Opponent), Ref("defensive_actions_high")),
      teamOnly = true
    ),
    MetricDef(
      "distance_covered",
      "m",
      Tracking,
      requires = 1,
      fallback = Some("involvements"),
      basis = Basis.Tracking
    ),
    MetricDef(
      "sprints",
      "sprints",
      Tracking,
      requires = 1,
      fallback = Some("involvements"),
      basis = Basis.Tracking
    )
  )

  val byId: Map[String, MetricDef] = all.map(m => m.id -> m).toMap

  def apply(id: String): MetricDef =
    byId.getOrElse(id, throw IllegalArgumentException(s"unknown metric '$id'"))

  /** Metrics stored in per-minute buckets (everything else is derived from them at query time). */
  val additive: Vector[MetricDef] =
    all.filter(m => m.calc.isInstanceOf[Count] || m.calc.isInstanceOf[Sum])

  /** What one enriched event adds to each additive metric it counts toward. */
  def contributions(e: Enriched): Vector[(String, Double)] =
    additive.flatMap { m =>
      m.calc match
        case Count(p) if p(e)  => Some(m.id -> 1.0)
        case Sum(p, v) if p(e) => Some(m.id -> v(e))
        case _                 => None
    }

  /** Whether an event counts toward an additive metric (used to list evidence). */
  def counts(id: String, e: Enriched): Boolean = apply(id).calc match
    case Count(p)  => p(e)
    case Sum(p, _) => p(e)
    case _         => false
