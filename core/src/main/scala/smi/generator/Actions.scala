package smi.generator

import smi.model.*

/** Open-play actions: pressure and duels, passes and shots. */
private[generator] trait Actions:
  this: Simulation =>

  /** The defending team may press the carrier and challenge. Returns true if the carrier keeps the
    * ball and should act now.
    */
  def contest(): Boolean =
    val d = 1 - team
    val k = knobs(d)
    val zone = if ball.x < 35 then 0.16 else if ball.x < 70 then 0.24 else 0.32
    // Style matters most for pressing high, i.e. in the carrier's own half.
    val style = if ball.x < 52.5 then k.press else 0.45 + 0.5 * (k.press - 0.45)
    // A counter-goal beat for the defenders makes them win the ball in their own half.
    val win = if ball.x > 52.5 then boost(BeatKind.CounterGoal, d) else 1.0
    // A red-card beat makes the defenders hunt the ball: more pressure and more challenges.
    val redBoost = boost(BeatKind.RedCard, d)
    val hunt = StrictMath.sqrt(redBoost)
    if !rng.chance(math.min(0.9, zone * (0.3 + 1.6 * style) * win * hunt)) then true
    else
      val spot = ball.mirror.clamped.rounded
      val presser = nearest(d, spot)
      val carrier = teams(team).player(holder).playerId
      emitEvent(
        EventType.Pressure,
        d,
        pid(d, presser),
        Some(spot),
        details = Some(
          PressureDetails(
            carrier,
            durationS = Some(Grid.round1(rng.between(0.5, 2.0))),
            isCounterpress = Some(clockMs - possStartMs < 5_000)
          )
        )
      )
      underPressure = true
      advance(0.3)
      if !rng.chance(math.min(0.9, 0.28 * (0.6 + k.press) * win * hunt)) then true
      else
        // Defenders rarely risk a foul in their own box (that would be a penalty).
        val inBox = if Grid.inBox(ball) then 0.12 else 1.0
        // A booked defender eases off, unless a red-card beat is pushing his team.
        val booked = if teams(d).isBooked(teams(d).player(presser).playerId) then 0.35 else 1.0
        val careful = inBox * (if redBoost > 1 then 1.0 else booked)
        val foulChance = 0.35 * (0.5 + k.aggression) * (1 + 0.5 * (redBoost - 1)) * careful
        if rng.chance(math.min(0.9, foulChance)) then
          foul(d, presser)
          false
        else duel(d, presser, spot, win)

  private def duel(d: Int, presser: Int, spot: Pt, win: Double): Boolean =
    if rng.chance(0.5) then
      val wins = rng.chance(math.min(0.85, 0.55 * win))
      emitEvent(
        EventType.Tackle,
        d,
        pid(d, presser),
        Some(spot),
        Some(if wins then Outcome.Won else Outcome.Lost),
        Some(TargetDetails(pid(team, holder)))
      )
      advance(0.8)
      if wins then
        changePossession(
          PossessionCause.Tackle,
          spot,
          Some(presser),
          None,
          rng.between(0.5, 1.5),
          wonInPlay = true
        )
      !wins
    else
      val wins = rng.chance(0.5 / win)
      emitEvent(
        EventType.Dribble,
        team,
        pid(team, holder),
        Some(ball),
        Some(if wins then Outcome.Won else Outcome.Lost),
        Some(TargetDetails(pid(d, presser))),
        pressured = true
      )
      advance(0.8)
      if wins then
        ball = Pt(ball.x + rng.between(3, 6), ball.y).clamped.rounded
        underPressure = false
      else
        changePossession(
          PossessionCause.Tackle,
          spot,
          Some(presser),
          None,
          rng.between(0.5, 1.5),
          wonInPlay = true
        )
      wins

  def shotChance: Double =
    val base = Grid.shotChance(ball)
    val counter = if counterBoost > 1 && Grid.inFinalThird(ball) then counterBoost else 1.0
    math.min(0.9, base * counter)

  def pass(passType: PassType): Unit =
    val from = ball
    val receiver = chooseReceiver(from)
    val aim = position(receiver, from)
    val spread = 2.0 + from.dist(aim) * 0.08
    val target =
      Pt(aim.x + rng.gaussian() * spread, aim.y + rng.gaussian() * spread).clamped.rounded
    val length = from.dist(target)
    val height =
      if passType == PassType.Corner || length > 32 then
        rng.weighted(Seq(PassHeight.High -> 0.7, PassHeight.Low -> 0.2, PassHeight.Ground -> 0.1))
      else
        rng.weighted(Seq(PassHeight.Ground -> 0.85, PassHeight.Low -> 0.1, PassHeight.High -> 0.05))
    val completes = rng.chance(successChance(from, target, passType))
    val outcome = if completes then Outcome.Complete else failure(from, target, passType)
    val details = PassDetails(
      endX = target.x,
      endY = target.y,
      lengthM = Grid.round1(length),
      angleRad = Some(
        StrictMath.round(StrictMath.atan2(target.y - from.y, target.x - from.x) * 100) / 100.0
      ),
      height = Some(height),
      bodyPart = Some(if passType == PassType.ThrowIn then BodyPart.Other else footOrHead(height)),
      recipientId = Option.when(completes)(teams(team).player(receiver).playerId),
      passType = Some(passType),
      isThroughBall = Some(height == PassHeight.Ground && target.x - from.x > 15 && target.x > 70),
      isCross = Some(Grid.inBox(target) && (from.y < 15 || from.y > 53) && from.x > 75),
      isSwitch = Some(math.abs(target.y - from.y) > 35)
    )
    val id = emitEvent(
      EventType.Pass,
      team,
      pid(team, holder),
      Some(from),
      Some(outcome),
      Some(details),
      underPressure
    )
    underPressure = false
    val speed = height match
      case PassHeight.Ground => 14.0
      case PassHeight.Low    => 16.0
      case PassHeight.High   => 18.0
    advance(length / speed + 0.2)
    if completes then
      holder = receiver
      ball = target
      lastPass = Some((id, receiver))
      passesInPossession += 1
      // On a boosted counter the receiver plays it on quickly.
      val urgency = if counterBoost > 1 then StrictMath.sqrt(counterBoost) else 1.0
      advance(rng.between(0.8, 3.6) / (knobs(team).tempo * urgency))
    else afterFailedPass(outcome, from, target, receiver)

  private def chooseReceiver(from: Pt): Int =
    val directness = if counterBoost > 1 then 1.0 else knobs(team).directness
    val forwardCoef = 0.2 + 0.85 * directness
    val decay = 12 + 8.6 * directness
    val options = teams(team).filled.filter(_ != holder).map { j =>
      val p = position(j, from)
      val d = p.dist(from)
      val forward = 1 + forwardCoef * Grid.clamp((p.x - from.x) / 15, -0.8, 1.2)
      val keeper = if j == 0 then 0.25 else 1.0
      val runner = if counterBoost > 1 && j >= 8 then counterBoost else 1.0
      j -> (if d < 3 then 0.0 else StrictMath.exp(-d / decay) * forward * keeper * runner)
    }
    rng.weighted(options)

  private def successChance(from: Pt, to: Pt, passType: PassType): Double =
    if passType == PassType.Corner then 0.45
    else if Grid.inOwnBox(to) then 0.97 // back passes to the keeper rarely go wrong
    else
      val length = from.dist(to)
      var p = 0.95 - 0.0045 * math.max(0, length - 12)
      if Grid.inFinalThird(to) then p -= 0.07
      if Grid.inBox(to) then p -= 0.12
      if to.x - from.x > 20 then p -= 0.05
      if passType == PassType.ThrowIn then p += 0.03
      if underPressure then p -= 0.08
      if counterBoost > 1 then p += 0.08
      Grid.clamp(p, 0.35, 0.97)

  private def failure(from: Pt, to: Pt, passType: PassType): Outcome =
    val offsideRisk = passType == PassType.OpenPlay && to.x > 70 && to.x - from.x > 8
    if Grid.inOwnBox(to) then Outcome.Out // a miscued back pass goes behind for a corner
    else if offsideRisk && rng.chance(0.06) then Outcome.Offside
    else
      rng.weighted(
        Seq(Outcome.Incomplete -> 0.45, Outcome.Intercepted -> 0.42, Outcome.Out -> 0.13)
      )

  def footOrHead(height: PassHeight): BodyPart =
    if height == PassHeight.High && rng.chance(0.1) then BodyPart.Head
    else rng.weighted(Seq(BodyPart.RightFoot -> 0.72, BodyPart.LeftFoot -> 0.28))

  def shoot(situation: ShotSituation): Unit =
    val from = ball
    val distance = from.dist(Grid.Goal)
    val labelled =
      if situation == ShotSituation.OpenPlay && isCounter then ShotSituation.Counter else situation
    val openPlay = situation == ShotSituation.OpenPlay
    val header = openPlay && distance < 14 && rng.chance(0.15)
    val bodyPart = if header then BodyPart.Head else footOrHead(PassHeight.Ground)
    val goalChance = situation match
      case ShotSituation.Penalty  => 0.76
      case ShotSituation.FreeKick => 0.07
      case _ =>
        val base = (0.46 * StrictMath.exp(-distance / 7) + 0.02) * (if header then 0.6 else 1.0)
        val counter = if counterBoost > 1 then 1 + (counterBoost - 1) * 2.2 else 1.0
        math.min(0.9, base * counter)
    val outcome =
      if rng.chance(goalChance) then Outcome.Goal
      else if situation == ShotSituation.Penalty then
        rng.weighted(Seq(Outcome.Saved -> 0.7, Outcome.OffTarget -> 0.25, Outcome.Post -> 0.05))
      else
        rng.weighted(
          Seq(
            Outcome.Saved -> 0.30,
            Outcome.Blocked -> 0.28,
            Outcome.OffTarget -> 0.38,
            Outcome.Post -> 0.04
          )
        )
    val assist = lastPass.collect { case (id, receiver) if receiver == holder => id }
    emitEvent(
      EventType.Shot,
      team,
      pid(team, holder),
      Some(from),
      Some(outcome),
      Some(ShotDetails(Some(bodyPart), labelled, assistEventId = assist)),
      underPressure
    )
    underPressure = false
    advance(1.0)
    afterShot(outcome, from, labelled)
