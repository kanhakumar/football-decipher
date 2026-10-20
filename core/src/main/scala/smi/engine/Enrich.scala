package smi.engine

import smi.model.*

/** An event with fields the engine derives from it. The raw event is never modified. All fields
  * here come from event data alone (basis `event_data`).
  */
final case class Enriched(
    event: Event,
    xg: Option[Double] = None,
    expectedCompletion: Option[Double] = None,
    progressive: Boolean = false,
    finalThirdEntry: Boolean = false,
    boxEntry: Boolean = false,
    zone: Option[Int] = None,
    endZone: Option[Int] = None
):
  def passDifficulty: Option[Double] = expectedCompletion.map(1 - _)
  def is(t: EventType): Boolean = event.`type` == t
  def completed: Boolean = event.outcome.contains(Outcome.Complete)

object Enrich:
  private def sigmoid(z: Double): Double = 1 / (1 + StrictMath.exp(-z))

  /** xG from distance and goal-mouth angle. Hand-set logistic coefficients, roughly realistic
    * (about 0.27 from 6 m central, 0.11 from the penalty spot in open play, 0.03 from 20 m) and set
    * so mean xG tracks mean goals in the synthetic matches. Not fitted to any data (ADR-010).
    */
  def xg(x: Double, y: Double, bodyPart: Option[BodyPart], situation: ShotSituation): Double =
    situation match
      case ShotSituation.Penalty => 0.76
      case _ =>
        val d = Zones.distanceToGoal(x, y)
        val base = sigmoid(-1.75 - 0.11 * d + 1.3 * Zones.goalAngle(x, y))
        val header = if bodyPart.contains(BodyPart.Head) then 0.6 else 1.0
        val freeKick = if situation == ShotSituation.FreeKick then 0.8 else 1.0
        base * header * freeKick

  /** Chance an average pass like this one is completed. Hand-set logistic, like `xg`. */
  def expectedCompletion(e: Event, p: PassDetails): Double =
    val forward = p.endX - e.x.getOrElse(p.endX)
    val z = 3.2 - 0.045 * p.lengthM -
      (if forward > 15 then 0.6 else 0) -
      (if Zones.third(p.endX) == 2 then 0.5 else 0) -
      (if Zones.inBox(p.endX, p.endY) then 0.6 else 0) -
      (if e.underPressure.contains(true) then 0.5 else 0) -
      (if p.height.contains(PassHeight.High) then 0.4 else 0)
    sigmoid(z)

  /** Completed and at least 10 m closer to goal, and at least a quarter of the remaining distance.
    */
  def isProgressive(x: Double, y: Double, p: PassDetails): Boolean =
    val before = Zones.distanceToGoal(x, y)
    val gain = before - Zones.distanceToGoal(p.endX, p.endY)
    gain >= math.max(10.0, 0.25 * before)

  def apply(e: Event): Enriched =
    val zone = for x <- e.x; y <- e.y yield Zones.zone(x, y)
    (e.details, e.x, e.y) match
      case (Some(s: ShotDetails), Some(x), Some(y)) =>
        Enriched(e, xg = Some(xg(x, y, s.bodyPart, s.situation)), zone = zone)
      case (Some(p: PassDetails), Some(x), Some(y)) =>
        val done = e.outcome.contains(Outcome.Complete)
        Enriched(
          e,
          expectedCompletion = Some(expectedCompletion(e, p)),
          progressive = done && isProgressive(x, y, p),
          finalThirdEntry = done && Zones.third(x) < 2 && Zones.third(p.endX) == 2,
          boxEntry = done && !Zones.inBox(x, y) && Zones.inBox(p.endX, p.endY),
          zone = zone,
          endZone = Some(Zones.zone(p.endX, p.endY))
        )
      case _ => Enriched(e, zone = zone)
