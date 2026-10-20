package smi.generator

import scala.collection.mutable
import smi.model.*

/** Fouls, cards and substitutions. */
private[generator] trait Discipline:
  this: Simulation =>

  /** Per team: remaining substitution windows as (display minute, number of changes). */
  private val subPlan = Vector.fill(2)(mutable.Queue.empty[(Double, Int)])
  private val subbedSlots = Vector.fill(2)(mutable.Set.empty[Int])

  /** Defender `fouler` of team `d` fouls the carrier. The fouled team restarts. */
  def foul(d: Int, fouler: Int): Unit =
    val k = knobs(d)
    val redBoost = boost(BeatKind.RedCard, d)
    val inBox = Grid.inBox(ball)
    val offender = teams(d).player(fouler).playerId
    val straightRed = rng.chance(math.min(0.9, 0.004 + 0.12 * (redBoost - 1)))
    val card =
      if straightRed then Card.Red
      else if rng.chance(0.17 * (0.5 + k.aggression)) then
        if teams(d).addYellow(offender) >= 2 then Card.Red else Card.Yellow
      else Card.None
    emitEvent(
      EventType.Foul,
      d,
      Some(offender),
      Some(ball.mirror.clamped.rounded),
      details = Some(FoulDetails(teams(team).player(holder).playerId, card, Some(inBox)))
    )
    if card == Card.Red then
      teams(d).sendOff(fouler)
      beatHappened(BeatKind.RedCard, d)
    underPressure = false
    lastPass = None
    advance(rng.between(20, 40) + (if card == Card.None then 0 else 15))
    if inBox then
      ball = Pt(94, 34) // penalty spot, 11 m from goal
      holder = taker(team)
      restart = Some(Restart(SetPieceKind.Penalty))
    else restart = Some(Restart(SetPieceKind.FreeKick)) // the fouled player takes it where he fell

  /** Draws each team's substitution windows for the second half: 3 to 5 changes, 1 to 3 windows. */
  def planSubstitutions(): Unit =
    subPlan.foreach { plan =>
      val total = 3 + (rng.uniform() * 3).toInt
      val windows = math.min(total, 1 + (rng.uniform() * 3).toInt)
      val minutes = Vector.fill(windows)(rng.between(55, 85)).sorted
      val counts =
        (0 until windows).map(w => total / windows + (if w < total % windows then 1 else 0))
      plan ++= minutes.zip(counts)
    }

  /** At a dead ball, makes any substitutions whose window has opened. */
  def maybeSubstitute(): Unit =
    if period == 2 then
      for t <- 0 to 1 do
        while subPlan(t).headOption.exists(_._1 <= minute) do
          val (_, count) = subPlan(t).dequeue()
          val made = (1 to count).count(_ => substituteOne(t))
          if made > 0 then advance(rng.between(20, 40))

  private def substituteOne(t: Int): Boolean =
    val candidates =
      teams(t).outfield.filterNot(subbedSlots(t)).map(s => s -> (1.0 + Squads.group(s)))
    candidates.nonEmpty && {
      val slot = rng.weighted(candidates)
      teams(t).substitute(slot).exists { (off, on) =>
        subbedSlots(t) += slot
        emitEvent(
          EventType.Substitution,
          t,
          Some(off.playerId),
          details = Some(SubstitutionDetails(off.playerId, on.playerId))
        )
        true
      }
    }
