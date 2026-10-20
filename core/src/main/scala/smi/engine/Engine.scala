package smi.engine

import smi.model.*

/** Who a number belongs to. */
sealed trait Holder
object Holder:
  final case class Team(id: TeamId) extends Holder
  final case class Player(id: PlayerId) extends Holder

/** A per-minute bucket: minute of period, so first-half stoppage never collides with the second
  * half.
  */
final case class BucketKey(period: Int, minute: Int):
  def displayMinute: Int = Engine.baseMinute(period) + minute

object BucketKey:
  def of(e: Event): BucketKey = BucketKey(e.period, (e.periodClockS / 60).toInt)

/** A window over display minutes (the second half starts at 45), optionally within one period. */
final case class Window(
    period: Option[Int] = None,
    fromMinute: Double = 0,
    toMinute: Double = Double.MaxValue
):
  def contains(k: BucketKey): Boolean =
    period.forall(_ == k.period) && k.displayMinute >= fromMinute && k.displayMinute < toMinute
  def contains(e: Event): Boolean = contains(BucketKey.of(e))

object Window:
  val Match: Window = Window()

/** A queried number with where it came from. `value` is empty when undefined (a ratio over zero).
  */
final case class MetricValue(
    metric: String,
    value: Option[Double],
    unit: String,
    basis: Basis,
    capabilityLevel: Int,
    via: Option[String] = None
)

/** Order-dependent match state, rebuilt from the event store when first asked for. */
final case class Derived(
    score: Map[TeamId, Int],
    period: Int,
    periodClockS: Double,
    possessionTeam: Option[TeamId],
    possessionId: Option[PossessionId],
    onPitch: Map[TeamId, Set[PlayerId]],
    yellowCards: Map[PlayerId, Int],
    sentOff: Set[PlayerId]
)

final case class EngineState(
    meta: Option[MatchMeta] = None,
    lineups: Map[TeamId, Lineup] = Map.empty,
    events: Map[EventId, Enriched] = Map.empty,
    buckets: Map[BucketKey, Map[(String, Holder), Double]] = Map.empty,
    /** Capability level of the data seen so far. Stays 0 until tracking metrics exist (step 9). */
    capability: Int = 0
):
  /** Events in match order. */
  lazy val ordered: Vector[Enriched] =
    events.values.toVector.sortBy(e =>
      (e.event.period, e.event.periodClockS, e.event.eventId.value)
    )

  lazy val derived: Derived = Engine.derive(this)

  def teams: Vector[TeamId] =
    meta
      .map(m => Vector(m.homeTeamId, m.awayTeamId))
      .getOrElse(lineups.keys.toVector.sortBy(_.value))

  def opponent(team: TeamId): Option[TeamId] = teams.find(_ != team)

/** The stats engine: canonical envelopes in, facts out. Pure; no generator code (ADR-010). */
object Engine:
  val empty: EngineState = EngineState()

  def baseMinute(period: Int): Int = period match
    case 1 => 0
    case 2 => 45
    case 3 => 90
    case 4 => 105
    case _ => 120

  def replay(envelopes: Iterable[Envelope]): EngineState = envelopes.foldLeft(empty)(apply)

  def apply(s: EngineState, env: Envelope): EngineState = env.payload match
    case Payload.Meta(m)                   => s.copy(meta = Some(m))
    case Payload.Team(l)                   => s.copy(lineups = s.lineups.updated(l.teamId, l))
    case Payload.Ev(e)                     => upsert(s, e)
    case Payload.Delete(d)                 => remove(s, d.eventId)
    case Payload.Frame(_) | Payload.Raw(_) => s

  // ---- updates: subtract the old version's contributions, add the new one's ----

  private def holders(e: Event): Vector[Holder] =
    Holder.Team(e.teamId) +: e.playerId.map(Holder.Player(_)).toVector

  private def addTo(
      buckets: Map[BucketKey, Map[(String, Holder), Double]],
      e: Enriched,
      sign: Double
  ): Map[BucketKey, Map[(String, Holder), Double]] =
    val key = BucketKey.of(e.event)
    val updated =
      Metrics.contributions(e).foldLeft(buckets.getOrElse(key, Map.empty)) { case (b, (id, v)) =>
        holders(e.event).foldLeft(b) { (acc, h) =>
          val next = acc.getOrElse((id, h), 0.0) + sign * v
          if math.abs(next) < 1e-9 then acc - ((id, h)) else acc.updated((id, h), next)
        }
      }
    if updated.isEmpty then buckets - key else buckets.updated(key, updated)

  private def upsert(s: EngineState, e: Event): EngineState =
    val enriched = Enrich(e)
    val without = s.events.get(e.eventId).fold(s.buckets)(old => addTo(s.buckets, old, -1))
    s.copy(events = s.events.updated(e.eventId, enriched), buckets = addTo(without, enriched, +1))

  private def remove(s: EngineState, id: EventId): EngineState =
    s.events
      .get(id)
      .fold(s)(old => s.copy(events = s.events - id, buckets = addTo(s.buckets, old, -1)))

  // ---- order-dependent state ----

  def derive(s: EngineState): Derived =
    val start = Derived(
      score = s.teams.map(_ -> 0).toMap,
      period = 1,
      periodClockS = 0,
      possessionTeam = None,
      possessionId = None,
      onPitch = s.lineups.map((t, l) => t -> l.starters.map(_.playerId).toSet),
      yellowCards = Map.empty,
      sentOff = Set.empty
    )
    s.ordered.map(_.event).foldLeft(start) { (d, e) =>
      val moved = d.copy(
        period = e.period,
        periodClockS = e.periodClockS,
        possessionId = e.possessionId.orElse(d.possessionId)
      )
      e.details match
        case Some(_: ShotDetails) if e.outcome.contains(Outcome.Goal) =>
          moved.copy(score = moved.score.updated(e.teamId, moved.score.getOrElse(e.teamId, 0) + 1))
        case Some(pc: PossessionChangeDetails) => moved.copy(possessionTeam = Some(pc.toTeam))
        case Some(sub: SubstitutionDetails) =>
          val now = moved.onPitch.getOrElse(e.teamId, Set.empty) - sub.playerOffId + sub.playerOnId
          moved.copy(onPitch = moved.onPitch.updated(e.teamId, now))
        case Some(f: FoulDetails) if f.card != Card.None =>
          val p = e.playerId
          val yellows = p.fold(moved.yellowCards)(id =>
            if f.card == Card.Yellow then
              moved.yellowCards.updated(id, moved.yellowCards.getOrElse(id, 0) + 1)
            else moved.yellowCards
          )
          val off = if f.card == Card.Red then p.toSet else Set.empty[PlayerId]
          moved.copy(
            yellowCards = yellows,
            sentOff = moved.sentOff ++ off,
            onPitch =
              moved.onPitch.updated(e.teamId, moved.onPitch.getOrElse(e.teamId, Set.empty) -- off)
          )
        case _ if e.`type` == EventType.Pass || e.`type` == EventType.Shot =>
          moved.copy(possessionTeam = Some(e.teamId))
        case _ => moved
    }

  // ---- queries ----

  private def sum(s: EngineState, id: String, hs: Seq[Holder], w: Window): Double =
    s.buckets.iterator
      .filter((k, _) => w.contains(k))
      .map((_, b) => hs.map(h => b.getOrElse((id, h), 0.0)).sum)
      .sum

  private def resolve(s: EngineState, ref: Ref, holder: Holder): Seq[Holder] =
    (ref.of, holder) match
      case (Of.Own, h)                   => Seq(h)
      case (Of.Opponent, Holder.Team(t)) => s.opponent(t).map(Holder.Team(_)).toSeq
      case (Of.Both, Holder.Team(_))     => s.teams.map(Holder.Team(_))
      case (of, h) => throw IllegalArgumentException(s"$of is only defined for teams, not $h")

  private def raw(s: EngineState, m: MetricDef, holder: Holder, w: Window): Option[Double] =
    m.calc match
      case Calc.Count(_) | Calc.Sum(_, _) => Some(sum(s, m.id, Seq(holder), w))
      case Calc.Ratio(num, den, scale) =>
        val d = sum(s, den.metric, resolve(s, den, holder), w)
        Option.when(d != 0)(scale * sum(s, num.metric, resolve(s, num, holder), w) / d)
      case Calc.Tracking => None

  /** A metric for a team or player over a window, with its basis. Falls back when the data a metric
    * requires is missing, and says so (`basis = event_estimate`, `via`).
    */
  def query(s: EngineState, id: String, holder: Holder, w: Window = Window.Match): MetricValue =
    val m = Metrics(id)
    if m.teamOnly && holder.isInstanceOf[Holder.Player] then
      throw IllegalArgumentException(s"$id is a team metric")
    if s.capability >= m.requires && m.calc != Calc.Tracking then
      MetricValue(id, raw(s, m, holder, w), m.unit, m.basis, m.requires)
    else
      m.fallback.map(Metrics(_)) match
        case Some(f) =>
          MetricValue(
            id,
            raw(s, f, holder, w),
            f.unit,
            Basis.EventEstimate,
            s.capability,
            Some(f.id)
          )
        case None => MetricValue(id, None, m.unit, m.basis, m.requires)

  def team(s: EngineState, id: String, team: TeamId, w: Window = Window.Match): MetricValue =
    query(s, id, Holder.Team(team), w)

  def player(s: EngineState, id: String, player: PlayerId, w: Window = Window.Match): MetricValue =
    query(s, id, Holder.Player(player), w)

  /** Value per minute of a period, for every minute that has any data in that period. */
  def series(
      s: EngineState,
      id: String,
      holder: Holder,
      period: Int
  ): Vector[(Int, Option[Double])] =
    val minutes = s.buckets.keys.filter(_.period == period).map(_.minute)
    if minutes.isEmpty then Vector.empty
    else
      (0 to minutes.max).toVector.map { m =>
        val w = Window(Some(period), baseMinute(period) + m, baseMinute(period) + m + 1)
        m -> query(s, id, holder, w).value
      }

  /** The events behind a number: exactly those that contributed to it in the window. */
  def evidence(
      s: EngineState,
      id: String,
      holder: Holder,
      w: Window = Window.Match
  ): Vector[EventId] =
    val m = Metrics(id)
    def matching(metric: String, hs: Seq[Holder]) = s.ordered.filter { e =>
      w.contains(e.event) && Metrics.counts(metric, e) && hs.exists {
        case Holder.Team(t)   => e.event.teamId == t
        case Holder.Player(p) => e.event.playerId.contains(p)
      }
    }
    val events = m.calc match
      case Calc.Count(_) | Calc.Sum(_, _) => matching(id, Seq(holder))
      case Calc.Ratio(num, den, _) =>
        (matching(num.metric, resolve(s, num, holder)) ++ matching(
          den.metric,
          resolve(s, den, holder)
        )).distinct
      case Calc.Tracking => m.fallback.fold(Vector.empty)(f => matching(f, Seq(holder)))
    s.ordered.map(_.event.eventId).filter(events.map(_.event.eventId).toSet)
