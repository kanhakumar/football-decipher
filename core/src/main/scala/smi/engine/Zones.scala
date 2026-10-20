package smi.engine

import smi.Pitch

/** The engine's own pitch geometry, in team-relative metres (attacking toward x = 105). */
object Zones:
  val DefensiveThirdEnd = 35.0
  val AttackingThirdStart = 70.0
  val Halfway: Double = Pitch.Length / 2
  val GoalY: Double = Pitch.Width / 2
  val GoalWidth = 7.32
  val BoxDepth = 16.5
  val BoxHalfWidth = 20.16

  /** 0 defensive, 1 middle, 2 attacking third. */
  def third(x: Double): Int =
    if x < DefensiveThirdEnd then 0 else if x < AttackingThirdStart then 1 else 2

  def inBox(x: Double, y: Double): Boolean =
    x >= Pitch.Length - BoxDepth && math.abs(y - GoalY) <= BoxHalfWidth

  /** Central area just outside the box, where many chances are created. */
  def inZone14(x: Double, y: Double): Boolean =
    x >= AttackingThirdStart && x < Pitch.Length - BoxDepth && math.abs(y - GoalY) <= 9.16

  /** 18 zones: 6 columns of 17.5 m by 3 lanes (right, centre, left); 0 is the own right corner. */
  def zone(x: Double, y: Double): Int =
    val col = math.min(5, math.max(0, (x / (Pitch.Length / 6)).toInt))
    val lane = math.min(2, math.max(0, (y / (Pitch.Width / 3)).toInt))
    col * 3 + lane

  def distanceToGoal(x: Double, y: Double): Double = StrictMath.hypot(Pitch.Length - x, GoalY - y)

  /** Angle (radians) the goal mouth covers as seen from (x, y). */
  def goalAngle(x: Double, y: Double): Double =
    val dx = math.max(Pitch.Length - x, 0.01)
    val near = StrictMath.atan2(GoalY - GoalWidth / 2 - y, dx)
    val far = StrictMath.atan2(GoalY + GoalWidth / 2 - y, dx)
    math.abs(far - near)
