package smi

/** Pitch geometry shared by every module. Coordinates are team-relative: each team attacks toward x
  * \= Length.
  */
object Pitch:
  val Length: Double = 105.0
  val Width: Double = 68.0

  def contains(x: Double, y: Double): Boolean =
    x >= 0 && x <= Length && y >= 0 && y <= Width
