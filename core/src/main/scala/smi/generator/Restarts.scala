package smi.generator

import smi.model.*

/** Possession changes, what follows failed passes and shots, and dead-ball restarts. */
private[generator] trait Restarts:
  this: Simulation =>

  /** Starts a possession for team `t` at `spot` (in that team's frame) without an event. */
  def startPossession(
      t: Int,
      spot: Pt,
      slot: Int,
      how: Option[Restart],
      wonInPlay: Boolean = false
  ): Unit =
    team = t
    ball = spot.clamped.rounded
    holder = slot
    restart = how
    possession += 1
    sequenceIndex = 0
    lastPass = None
    underPressure = false
    possStartMs = clockMs
    possStartX = ball.x
    possWonInPlay = wonInPlay
    passesInPossession = 0
    counterBoost = if wonInPlay && ball.x < 52.5 then boost(BeatKind.CounterGoal, t) else 1.0

  /** Emits a possession_change and hands the ball to the other team at `spot` (their frame). */
  def changePossession(
      cause: PossessionCause,
      spot: Pt,
      newHolder: Option[Int],
      how: Option[Restart],
      deadS: Double,
      wonInPlay: Boolean = false
  ): Unit =
    val (from, to) = (team, 1 - team)
    emitEvent(
      EventType.PossessionChange,
      to,
      details = Some(PossessionChangeDetails(teamId(from), teamId(to), cause))
    )
    val at = spot.clamped.rounded
    startPossession(to, at, newHolder.getOrElse(nearest(to, at)), how, wonInPlay)
    advance(deadS)

  def takeRestart(r: Restart): Unit =
    restart = None
    emitEvent(
      EventType.SetPiece,
      team,
      pid(team, holder),
      Some(ball),
      details = Some(SetPieceDetails(r.kind))
    )
    r.kind match
      case SetPieceKind.Penalty => shoot(ShotSituation.Penalty)
      case SetPieceKind.FreeKick if inShootingRange && rng.chance(0.3) =>
        shoot(ShotSituation.FreeKick)
      case SetPieceKind.FreeKick => pass(PassType.FreeKick)
      case SetPieceKind.Corner   => pass(PassType.Corner)
      case SetPieceKind.ThrowIn  => pass(PassType.ThrowIn)
      case SetPieceKind.GoalKick => pass(PassType.GoalKick)
      case SetPieceKind.KickOff  => pass(PassType.KickOff)

  private def inShootingRange: Boolean =
    ball.dist(Grid.Goal) < 28 && math.abs(ball.y - Grid.Goal.y) < 15

  /** Goal kick spot for the team about to take it, in its own frame. */
  private def goalKickSpot: Pt = Pt(5.5, if rng.chance(0.5) then 24.84 else 43.16)

  def afterFailedPass(outcome: Outcome, from: Pt, target: Pt, receiver: Int): Unit =
    val d = 1 - team
    val spot = target.mirror.clamped.rounded // the target seen by the defenders
    outcome match
      case Outcome.Out if Grid.inOwnBox(target) =>
        val flag = Pt(target.x, target.y).mirror // the corner flag on the defenders' attacking side
        val spot = Pt(104.5, if flag.y < 34 then 0.5 else 67.5)
        val winger = if spot.y < 34 then 8 else 10
        val kicker = if teams(d).isOnPitch(winger) then winger else nearest(d, spot)
        changePossession(
          PossessionCause.OutOfPlay,
          spot,
          Some(kicker),
          Some(Restart(SetPieceKind.Corner)),
          rng.between(20, 40)
        )
      case Outcome.Out if target.x > 103.5 =>
        changePossession(
          PossessionCause.OutOfPlay,
          goalKickSpot,
          Some(0),
          Some(Restart(SetPieceKind.GoalKick)),
          rng.between(20, 30)
        )
      case Outcome.Out =>
        val touchline = Pt(target.x, if target.y < 34 then 0.5 else 67.5).mirror
        changePossession(
          PossessionCause.OutOfPlay,
          touchline,
          None,
          Some(Restart(SetPieceKind.ThrowIn)),
          rng.between(12, 30)
        )
      case Outcome.Offside =>
        emitEvent(EventType.Offside, team, pid(team, receiver), Some(target))
        changePossession(
          PossessionCause.Other,
          spot,
          None,
          Some(Restart(SetPieceKind.FreeKick)),
          rng.between(15, 30)
        )
      case Outcome.Intercepted =>
        val interceptor = nearest(d, spot)
        emitEvent(
          EventType.Interception,
          d,
          pid(d, interceptor),
          Some(spot),
          details = Some(GenericDetails())
        )
        changePossession(
          PossessionCause.Interception,
          spot,
          Some(interceptor),
          None,
          rng.between(0.3, 1.0),
          wonInPlay = true
        )
      case _ if Grid.inBox(target) => defendBox(d, spot, target)
      case _ =>
        val loose = Pt(target.x + rng.gaussian() * 3, target.y + rng.gaussian() * 3)
        changePossession(PossessionCause.Other, loose.mirror, None, None, rng.between(1.0, 3.0))

  /** A failed ball into the box: the keeper claims it or a defender clears. */
  private def defendBox(d: Int, spot: Pt, target: Pt): Unit =
    if rng.chance(0.35) then
      emitEvent(
        EventType.GoalkeeperAction,
        d,
        pid(d, 0),
        Some(spot),
        details = Some(GkDetails(GkAction.Claim))
      )
      changePossession(PossessionCause.Other, spot, Some(0), None, rng.between(1.0, 3.0))
    else
      val clearer = nearest(d, spot)
      emitEvent(
        EventType.Clearance,
        d,
        pid(d, clearer),
        Some(spot),
        details = Some(GenericDetails())
      )
      if rng.chance(0.55) then
        val landing = Pt(spot.x + rng.between(15, 35), spot.y + rng.gaussian() * 8)
        changePossession(PossessionCause.Clearance, landing, None, None, rng.between(2.0, 4.0))
      else
        ball = Pt(target.x - rng.between(15, 30), target.y + rng.gaussian() * 8).clamped.rounded
        holder = nearest(team, ball)
        lastPass = None
        advance(rng.between(2.0, 4.0))

  def afterShot(outcome: Outcome, from: Pt, situation: ShotSituation): Unit =
    val d = 1 - team
    outcome match
      case Outcome.Goal =>
        goals(team) += 1
        if situation == ShotSituation.Counter then beatHappened(BeatKind.CounterGoal, team)
        changePossession(
          PossessionCause.Goal,
          Grid.Centre,
          Some(taker(d)),
          Some(Restart(SetPieceKind.KickOff)),
          rng.between(45, 80)
        )
      case Outcome.Saved =>
        val keeperSpot = Pt(4, 34 + rng.gaussian() * 2).clamped.rounded
        emitEvent(
          EventType.GoalkeeperAction,
          d,
          pid(d, 0),
          Some(keeperSpot),
          details = Some(GkDetails(GkAction.Save))
        )
        if rng.chance(0.5) then
          changePossession(PossessionCause.Shot, keeperSpot, Some(0), None, rng.between(3, 8))
        else corner()
      case Outcome.Blocked =>
        val spot = from.mirror.clamped.rounded
        val blocker = nearest(d, spot)
        emitEvent(EventType.Block, d, pid(d, blocker), Some(spot), details = Some(GenericDetails()))
        if rng.chance(0.5) then
          ball = Pt(from.x - 6, from.y).clamped.rounded
          holder = nearest(team, ball)
          lastPass = None
          advance(rng.between(0.5, 2.0))
        else
          changePossession(PossessionCause.Shot, spot, Some(blocker), None, rng.between(0.5, 2.0))
      case _ =>
        changePossession(
          PossessionCause.OutOfPlay,
          goalKickSpot,
          Some(0),
          Some(Restart(SetPieceKind.GoalKick)),
          rng.between(20, 35)
        )

  /** The attacking team keeps the ball with a corner. */
  private def corner(): Unit =
    ball = Pt(104.5, if rng.chance(0.5) then 0.5 else 67.5)
    val winger = if ball.y < 34 then 8 else 10
    holder = if teams(team).isOnPitch(winger) then winger else nearest(team, ball)
    restart = Some(Restart(SetPieceKind.Corner))
    lastPass = None
    advance(rng.between(20, 40))
