package smi.engine

import smi.model.*

/** A player's last tracked position and sprint state. */
final case class Runner(
    x: Double,
    y: Double,
    speedMs: Double,
    wallMs: Long,
    period: Int,
    fastSinceMs: Option[Long] = None,
    sprintCounted: Boolean = false
)

/** Workload aggregated from tracking frames, per minute and per player or team. Frames are folded
  * in and dropped; nothing here stores a frame (ADR-012).
  */
final case class Workload(
    hz: Double = 0,
    runners: Map[PlayerId, Runner] = Map.empty,
    frames: Map[BucketKey, Int] = Map.empty,
    sums: Map[BucketKey, Map[(String, Holder), Double]] = Map.empty,
    maxes: Map[BucketKey, Map[Holder, Double]] = Map.empty,
    lastFrameMs: Option[Long] = None,
    onPitch: Set[PlayerId] = Set.empty
):
  import Workload.*

  /** Folds in one frame. `home` turns frame coordinates (home team's frame) into each player's own
    * team frame for average positions.
    */
  def add(f: TrackingFrame, home: Option[TeamId] = None): Workload =
    val t = f.wallclockUtc.toInstant.toEpochMilli
    val key = BucketKey(f.period, (f.periodClockS / 60).toInt)
    val start = (runners, sums.getOrElse(key, Map.empty), maxes.getOrElse(key, Map.empty))
    val (nextRunners, bucket, top) = f.players.foldLeft(start) { case ((rs, b, m), p) =>
      val prev = rs
        .get(p.playerId)
        .filter(r => r.period == f.period && t - r.wallMs <= GapMs && t > r.wallMs)
      val holders = Vector(Holder.Player(p.playerId), Holder.Team(p.teamId))
      // Average position (team frame) for team shape: sums of x, y and samples per player.
      val own = home.forall(_ == p.teamId)
      val me = Holder.Player(p.playerId)
      val shaped = Vector(
        "shape_x" -> (if own then p.x else 105 - p.x),
        "shape_y" -> (if own then p.y else 68 - p.y),
        "shape_n" -> 1.0
      ).foldLeft(b)((acc, kv) => acc.updated((kv._1, me), acc.getOrElse((kv._1, me), 0.0) + kv._2))
      prev match
        case None =>
          (rs.updated(p.playerId, Runner(p.x, p.y, p.speedMs.getOrElse(0), t, f.period)), shaped, m)
        case Some(r) =>
          val d = StrictMath.hypot(p.x - r.x, p.y - r.y)
          val speed = p.speedMs.getOrElse(d / ((t - r.wallMs) / 1000.0))
          val fastSince = if speed >= SprintSpeed then r.fastSinceMs.orElse(Some(t)) else None
          val newSprint = fastSince.exists(s => !r.sprintCounted && t - s >= SprintHoldMs)
          def plus(acc: Map[(String, Holder), Double], id: String, v: Double) =
            if v == 0 then acc
            else holders.foldLeft(acc)((a, h) => a.updated((id, h), a.getOrElse((id, h), 0.0) + v))
          val withDistance = plus(shaped, "distance_covered", d)
          val withHigh =
            plus(withDistance, "high_speed_distance", if speed > HighSpeed then d else 0)
          val withSprints = plus(withHigh, "sprints", if newSprint then 1 else 0)
          val withTop =
            holders.foldLeft(m)((acc, h) => acc.updated(h, math.max(acc.getOrElse(h, 0.0), speed)))
          val runner = Runner(
            p.x,
            p.y,
            speed,
            t,
            f.period,
            fastSince,
            fastSince.isDefined && (r.sprintCounted || newSprint)
          )
          (rs.updated(p.playerId, runner), withSprints, withTop)
    }
    copy(
      hz = f.hz,
      runners = nextRunners,
      frames = frames.updated(key, frames.getOrElse(key, 0) + 1),
      sums = if bucket.isEmpty then sums else sums.updated(key, bucket),
      maxes = if top.isEmpty then maxes else maxes.updated(key, top),
      lastFrameMs = Some(t),
      onPitch = f.players.map(_.playerId).toSet
    )

object Workload:
  val GapMs = 1_000L
  val HighSpeed = 5.5
  val SprintSpeed = 7.0
  val SprintHoldMs = 1_000L

  /** Share of expected frames a window needs before tracking metrics answer from tracking. */
  val MinCoverage = 0.9

  /** Minutes on the pitch that set a player's own baseline for the fatigue index. */
  val BaselineMinutes = 15

/** Where a player is, if the data can say (ADR-012). */
sealed trait Located
object Located:
  final case class Available(x: Double, y: Double, speedMs: Double, wallMs: Long) extends Located
  final case class NotAvailable(reason: String) extends Located

/** Positions from whatever position data a match has. Callers must handle `NotAvailable`. */
trait PositionProvider:
  def position(player: PlayerId): Located

/** Positions from tracking frames folded into the engine. */
final class TrackingPositions(s: EngineState) extends PositionProvider:
  def position(player: PlayerId): Located =
    s.workload.lastFrameMs match
      case None                                   => Located.NotAvailable("no tracking data")
      case Some(_) if !s.workload.onPitch(player) => Located.NotAvailable("not on the pitch")
      case Some(last) if s.lastEventMs - last > Workload.GapMs =>
        Located.NotAvailable("tracking is stale")
      case Some(_) =>
        s.workload.runners.get(player) match
          case Some(r) => Located.Available(r.x, r.y, r.speedMs, r.wallMs)
          case None    => Located.NotAvailable("not tracked yet")
