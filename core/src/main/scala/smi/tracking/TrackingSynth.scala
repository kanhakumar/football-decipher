package smi.tracking

import java.time.Instant
import smi.Rng
import smi.model.*

final case class SynthConfig(
    seed: Long,
    hz: Double = 5,
    /** Pull an event's actor to the event spot this long before the event. */
    pullS: Double = 6.0
)

/** Formation-anchored 5 Hz tracking synthesized from a canonical feed (ADR-012). Data-source side,
  * like the generator: the engine only ever sees the frames it produces.
  *
  * Frames use the home team's frame (home attacks toward x = 105 all match).
  */
object TrackingSynth:
  /** Slot anchors in a team's own frame (attacking toward x = 105), by lineup slot label. */
  private val anchors: Map[String, (Double, Double)] = Map(
    "GK" -> (5, 34),
    "RB" -> (28, 8),
    "RCB" -> (22, 25),
    "LCB" -> (22, 43),
    "CB" -> (22, 34),
    "LB" -> (28, 60),
    "DM" -> (38, 34),
    "RCM" -> (48, 22),
    "LCM" -> (48, 46),
    "CM" -> (48, 34),
    "RW" -> (66, 10),
    "ST" -> (72, 34),
    "LW" -> (66, 58)
  )

  /** Top speed by role, m/s. */
  private def roleSpeed(p: Position): Double = p match
    case Position.GK                                                           => 7.0
    case Position.CB | Position.RB | Position.LB | Position.RWB | Position.LWB => 8.5
    case Position.CDM | Position.CM | Position.CAM | Position.RM | Position.LM => 9.0
    case _                                                                     => 9.5

  val MaxAcceleration = 3.5
  private val Sprinting = 5.5

  private final class Runner(
      val player: Player,
      val team: TeamId,
      val slot: (Double, Double),
      val topSpeed: Double,
      val phase: (Double, Double),
      var x: Double,
      var y: Double
  ):
    var vx = 0.0
    var vy = 0.0
    var fatigue = 0.0
    def speed: Double = StrictMath.hypot(vx, vy)

  private final case class Spot(x: Double, y: Double)

  /** Event location in the home team's frame. */
  private def home(e: Event, homeTeam: TeamId)(x: Double, y: Double): Spot =
    if e.teamId == homeTeam then Spot(x, y) else Spot(105 - x, 68 - y)

  /** Adds frames to a feed: merged by time, `seq` renumbered, event IDs unchanged. */
  def addFrames(feed: Vector[Envelope], cfg: SynthConfig): Vector[Envelope] =
    val frames = synthesize(feed, cfg)
    val merged = (feed.map(e => (e.emittedAt.toInstant, 0, e)) ++ frames.map(f =>
      (f.emittedAt.toInstant, 1, f)
    ))
      .sortBy((at, kind, e) => (at, kind, e.seq))
    merged.zipWithIndex.map { case ((_, _, e), i) => e.copy(seq = i.toLong) }

  /** Just the frames, as envelopes, for a canonical feed. */
  def synthesize(feed: Vector[Envelope], cfg: SynthConfig): Vector[Envelope] =
    val meta = feed.collectFirst { case Envelope(_, _, _, _, _, _, _, Payload.Meta(m)) => m }.get
    val lineups = feed.collect { case Envelope(_, _, _, _, _, _, _, Payload.Team(l)) =>
      l.teamId -> l
    }.toMap
    val events = feed.collect { case Envelope(_, _, _, _, Op.Create, _, _, Payload.Ev(e)) => e }
    val kickoff = meta.kickoffUtc.toInstant
    Synthesis(meta, lineups, events, kickoff, cfg).run()

  private final class Synthesis(
      meta: MatchMeta,
      lineups: Map[TeamId, Lineup],
      events: Vector[Event],
      kickoff: Instant,
      cfg: SynthConfig
  ):
    private val rng = Rng(cfg.seed ^ 0x7a3c5e1f2b4d6a8cL)
    private val homeTeam = meta.homeTeamId
    private val stepMs = StrictMath.round(1000 / cfg.hz)
    private def wall(e: Event): Long = e.wallclockUtc.toInstant.toEpochMilli - kickoff.toEpochMilli

    private def runner(p: Player, team: TeamId): Runner =
      val (ax, ay) = p.slot.flatMap(anchors.get).getOrElse(anchors("CM"))
      val slot = if team == homeTeam then (ax, ay) else (105 - ax, 68 - ay)
      val variation = 1 + rng.between(-0.04, 0.04)
      Runner(
        p,
        team,
        (ax, ay),
        roleSpeed(p.position) * variation,
        (rng.between(0, 6.28), rng.between(0, 6.28)),
        slot._1,
        slot._2
      )

    private val runners = scala.collection.mutable.LinkedHashMap.from(
      lineups.toVector
        .sortBy(_._1 != homeTeam)
        .flatMap((t, l) => l.starters.map(p => p.playerId -> runner(p, t)))
    )
    private val bench =
      lineups.values.flatMap(l => l.bench.map(p => p.playerId -> (p, l.teamId))).toMap
    private var refX = 50.0
    private var refY = 40.0
    private val ars = Array(52.5, 52.5)

    // Event lookup by time: index of the last event at or before a wall time.
    private val located = events.filter(e => e.x.isDefined && e.y.isDefined)
    private val times: Array[Long] = located.map(wall).toArray

    /** The team on the ball after each located event (last pass, shot or dribble so far). */
    private val attackingAt: Vector[TeamId] =
      located
        .scanLeft(homeTeam) { (team, e) =>
          if e.`type` == EventType.Pass || e.`type` == EventType.Shot || e.`type` == EventType.Dribble
          then e.teamId
          else team
        }
        .tail

    private def lastBefore(t: Long): Int =
      val i = java.util.Arrays.binarySearch(times, t)
      if i >= 0 then
        var j = i
        while j + 1 < times.size && times(j + 1) == t do j += 1
        j
      else -i - 2

    def run(): Vector[Envelope] =
      val periods =
        events.filter(_.`type` == EventType.PeriodStart).map(e => e.period -> wall(e)).toMap
      val ends = events.filter(_.`type` == EventType.PeriodEnd).map(e => e.period -> wall(e)).toMap
      val roster =
        events.filter(e => e.`type` == EventType.Substitution || isRed(e)).map(e => wall(e) -> e)
      var frameNo = 0L
      val out = Vector.newBuilder[Envelope]
      var pending = roster.sortBy(_._1)
      periods.keys.toVector.sorted.foreach { p =>
        val start = periods(p)
        if p > 1 then halfTimeRecovery()
        lineUp(start)
        var t = start
        while t <= ends.getOrElse(p, start) do
          while pending.nonEmpty && pending.head._1 <= t do
            changeRoster(pending.head._2)
            pending = pending.tail
          step(t)
          out += envelope(frame(frameNo, p, t, start), t)
          frameNo += 1
          t += stepMs
      }
      out.result()

    private def isRed(e: Event) = e.details.exists {
      case f: FoulDetails => f.card == Card.Red
      case _              => false
    }

    private def changeRoster(e: Event): Unit = e.details match
      case Some(s: SubstitutionDetails) =>
        runners.remove(s.playerOffId).foreach { off =>
          bench.get(s.playerOnId).foreach { (p, team) =>
            val fresh = runner(p.copy(slot = off.player.slot), team)
            fresh.x = 52.5
            fresh.y = if team == homeTeam then -2 else 70
            runners(s.playerOnId) = fresh
          }
        }
      case _ => e.playerId.foreach(runners.remove)

    private def halfTimeRecovery(): Unit = runners.values.foreach(r => r.fatigue *= 0.6)

    /** Puts everyone in place for a kick-off: the taker on the ball, the rest in shape. */
    private def lineUp(t: Long): Unit =
      val ctx = context(t)
      val pull = pulls(t)
      runners.values.foreach { r =>
        val (spot, _) = target(r, ctx, pull.get(r.player.playerId), t)
        r.x = spot.x
        r.y = spot.y
        r.vx = 0
        r.vy = 0
      }

    // ---- where things should be ----

    private final case class Context(
        ball: Spot,
        z: Double,
        ballSpeed: Double,
        dead: Boolean,
        attacking: TeamId
    )

    private def context(t: Long): Context =
      val i = lastBefore(t)
      if i < 0 then Context(Spot(52.5, 34), 0, 0, dead = true, homeTeam)
      else
        val e = located(i)
        val at = home(e, homeTeam)(e.x.get, e.y.get)
        val next = located.lift(i + 1)
        val gapMs = next.map(wall(_) - wall(e)).getOrElse(0L)
        val elapsed = (t - wall(e)) / 1000.0
        val (target, flightS, lofted) = e.details match
          case Some(pd: PassDetails) =>
            val speed = if pd.height.contains(PassHeight.High) then 18.0 else 14.0
            (
              home(e, homeTeam)(pd.endX, pd.endY),
              pd.lengthM / speed + 0.2,
              pd.height.contains(PassHeight.High)
            )
          case _ =>
            (
              next.map(n => home(n, homeTeam)(n.x.get, n.y.get)).getOrElse(at),
              math.max(gapMs / 1000.0, 0.1),
              false
            )
        val f = math.min(1.0, elapsed / math.max(flightS, 0.1))
        val ball = Spot(at.x + (target.x - at.x) * f, at.y + (target.y - at.y) * f)
        val dist = StrictMath.hypot(target.x - at.x, target.y - at.y)
        val z = if lofted && f < 1 then 4 * 6.0 * f * (1 - f) else 0.0
        val restart = next.exists(n => n.`type` == EventType.SetPiece)
        Context(
          ball,
          z,
          if f < 1 then dist / math.max(flightS, 0.1) else 0,
          restart && gapMs > 5_000 && f >= 1,
          attackingAt(i)
        )

    /** The actor of an imminent event, pulled to its spot. */
    private def pulls(t: Long): Map[PlayerId, Spot] =
      val from = lastBefore(t)
      val horizon = t + StrictMath.round(cfg.pullS * 1000)
      // Only events still to come (or happening now) pull their actor.
      val next = lastBefore(t - 1) + 1
      val ahead = located.drop(next).takeWhile(e => wall(e) <= horizon)
      val actors = ahead.flatMap(e => e.playerId.map(_ -> home(e, homeTeam)(e.x.get, e.y.get)))
      val receivers = located
        .lift(from)
        .toVector
        .flatMap(e =>
          e.details.collect {
            case pd: PassDetails if e.outcome.contains(Outcome.Complete) =>
              pd.recipientId.map(_ -> home(e, homeTeam)(pd.endX, pd.endY))
          }.flatten
        )
      (receivers ++ actors.reverse).toMap

    private def target(r: Runner, ctx: Context, pull: Option[Spot], t: Long): (Spot, Double) =
      pull match
        case Some(s) => (s, r.topSpeed)
        case None    =>
          // Shape: slot shifted with the ball and the line height, in the team's own frame.
          val own = r.team == homeTeam
          val bx = if own then ctx.ball.x else 105 - ctx.ball.x
          val by = if own then ctx.ball.y else 68 - ctx.ball.y
          val (ax, ay) = r.slot
          val keeper = r.player.position == Position.GK
          val line = if ctx.attacking == r.team then 5.0 else -5.0
          val sx =
            if keeper then ax + math.max(0, (bx - 52.5) * 0.22) else ax + (bx - 52.5) * 0.45 + line
          val sy = if keeper then ay + (by - 34) * 0.2 else ay + (by - 34) * 0.25
          val secs = t / 1000.0
          val wander = if keeper then 2.5 else 4.0
          val wx = sx + wander * StrictMath.sin(secs * 0.21 + r.phase._1)
          val wy = sy + wander * StrictMath.cos(secs * 0.17 + r.phase._2)
          val spot = if own then Spot(wx, wy) else Spot(105 - wx, 68 - wy)
          // Far from their place in the shape (a turnover, a long ball): a recovery run.
          val away = StrictMath.hypot(spot.x - r.x, spot.y - r.y)
          (spot, if away > 10 then r.topSpeed else if keeper then 3.5 else 3.9)

    // ---- movement ----

    private def step(t: Long): Unit =
      val ctx = context(t)
      val pull = pulls(t)
      val dt = stepMs / 1000.0
      runners.values.foreach { r =>
        val (goal, cap) = target(r, ctx, pull.get(r.player.playerId), t)
        val limit = math.min(cap, r.topSpeed) * (1 - 0.35 * r.fatigue)
        val dx = goal.x - r.x
        val dy = goal.y - r.y
        val dist = StrictMath.hypot(dx, dy)
        // Approach no faster than the player can still stop from, so nobody overshoots the spot.
        val braking = StrictMath.sqrt(2 * MaxAcceleration * 0.8 * dist)
        val want = math.min(limit, math.min(braking, dist / 0.6))
        val (wx, wy) = if dist < 1e-6 then (0.0, 0.0) else (dx / dist * want, dy / dist * want)
        val ax = wx - r.vx
        val ay = wy - r.vy
        val accel = StrictMath.hypot(ax, ay)
        val maxDv = MaxAcceleration * (1 - 0.3 * r.fatigue) * dt
        val k = if accel > maxDv then maxDv / accel else 1.0
        r.vx += ax * k
        r.vy += ay * k
        val sp = r.speed
        if sp > r.topSpeed then
          r.vx *= r.topSpeed / sp
          r.vy *= r.topSpeed / sp
        r.x = math.max(-5, math.min(110, r.x + r.vx * dt))
        r.y = math.max(-5, math.min(73, r.y + r.vy * dt))
        val fast = if r.speed > Sprinting then r.speed * dt else 0.0
        r.fatigue = math.min(0.6, r.fatigue + dt / 60 * 0.0035 + fast * 0.00012)
      }
      def chase(x: Double, goal: Double, max: Double) =
        x + math.max(-max * dt, math.min(max * dt, goal - x))
      refX = chase(refX, math.max(5, math.min(100, ctx.ball.x - 10)), 7)
      refY = chase(refY, math.max(5, math.min(63, ctx.ball.y + 10)), 7)
      ars(0) = chase(ars(0), math.max(52.5, math.min(105, ctx.ball.x)), 6.5)
      ars(1) = chase(ars(1), math.max(0, math.min(52.5, ctx.ball.x)), 6.5)
      lastCtx = ctx

    private var lastCtx = Context(Spot(52.5, 34), 0, 0, dead = true, homeTeam)

    // ---- output ----

    private def r2(v: Double) = StrictMath.round(v * 100) / 100.0

    private def frame(no: Long, period: Int, t: Long, periodStart: Long): TrackingFrame =
      val c = lastCtx
      TrackingFrame(
        frame = no,
        period = period,
        periodClockS = (t - periodStart) / 1000.0,
        wallclockUtc = Timestamp.of(kickoff.plusMillis(t)),
        videoTsMs = events.headOption.map(e => e.videoTsMs - wall(e)).getOrElse(10_000L) + t,
        hz = cfg.hz,
        ball = Ball(
          r2(c.ball.x),
          r2(c.ball.y),
          Some(r2(c.z)),
          Some(r2(math.min(60, c.ballSpeed))),
          Some(if c.dead then BallState.Dead else BallState.InPlay)
        ),
        players = runners.values.toList.map { r =>
          TrackedPlayer(
            r.player.playerId,
            r.team,
            r2(r.x),
            r2(r.y),
            Some(r2(r.speed)),
            Some(
              if r.player.position == Position.GK then TrackedRole.Goalkeeper
              else TrackedRole.Player
            )
          )
        },
        officials = Some(
          List(
            TrackedOfficial(OfficialId.unsafe("REF_01"), OfficialRole.Referee, r2(refX), r2(refY)),
            TrackedOfficial(
              OfficialId.unsafe("AR_01"),
              OfficialRole.AssistantReferee,
              r2(ars(0)),
              -1.5
            ),
            TrackedOfficial(
              OfficialId.unsafe("AR_02"),
              OfficialRole.AssistantReferee,
              r2(ars(1)),
              69.5
            )
          )
        )
      )

    private def envelope(f: TrackingFrame, t: Long): Envelope =
      Envelope(
        Envelope.SchemaVersion,
        0,
        meta.matchId,
        Source.Synthetic,
        Op.Create,
        Timestamp.of(kickoff.plusMillis(t + 100)),
        PayloadType.TrackingFrame,
        Payload.Frame(f)
      )
