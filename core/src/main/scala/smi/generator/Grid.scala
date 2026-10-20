package smi.generator

import smi.Pitch

/** A point in team-relative metres: the team in possession attacks toward x = 105. */
final case class Pt(x: Double, y: Double):
  def dist(o: Pt): Double = StrictMath.hypot(x - o.x, y - o.y)

  /** The same spot seen from the other team's frame. */
  def mirror: Pt = Pt(Pitch.Length - x, Pitch.Width - y)

  def clamped: Pt =
    Pt(Grid.clamp(x, 0.5, Pitch.Length - 0.5), Grid.clamp(y, 0.5, Pitch.Width - 0.5))

  /** Rounded to 0.1 m, the precision written to the feed. */
  def rounded: Pt = Pt(Grid.round1(x), Grid.round1(y))

/** The 12 x 8 zone grid (8.75 m x 8.5 m cells) that drives shot and pass-risk decisions. */
object Grid:
  val Cols = 12
  val Rows = 8
  val CellLength: Double = Pitch.Length / Cols
  val CellWidth: Double = Pitch.Width / Rows
  val Goal: Pt = Pt(Pitch.Length, Pitch.Width / 2)
  val Centre: Pt = Pt(Pitch.Length / 2, Pitch.Width / 2)

  final case class Cell(col: Int, row: Int):
    /** 0 = central lanes (rows 3-4), 1 = inside channels (2, 5), 2 = wide (0-1, 6-7). */
    def lane: Int = math.min(math.abs(2 * row - (Rows - 1)) / 2, 2)

  def cell(p: Pt): Cell =
    Cell(
      clamp((p.x / CellLength).toInt, 0, Cols - 1).toInt,
      clamp((p.y / CellWidth).toInt, 0, Rows - 1).toInt
    )

  /** Chance the ball carrier shoots instead of passing, by column from goal and lane. */
  private val shotTable: Map[Int, Vector[Double]] = Map(
    11 -> Vector(0.42, 0.24, 0.045),
    10 -> Vector(0.24, 0.13, 0.022),
    9 -> Vector(0.095, 0.04, 0.0),
    8 -> Vector(0.022, 0.0, 0.0)
  )

  def shotChance(p: Pt): Double =
    val c = cell(p)
    shotTable.get(c.col).map(_(c.lane)).getOrElse(0.0)

  /** Final third starts at column 8; the penalty area is roughly columns 10-11, lanes 0-1. */
  def inFinalThird(p: Pt): Boolean = cell(p).col >= 8
  def inBox(p: Pt): Boolean = p.x >= 88.5 && p.y >= 13.85 && p.y <= 54.15
  def inOwnBox(p: Pt): Boolean = p.x <= 16.5 && p.y >= 13.85 && p.y <= 54.15

  def clamp(v: Double, lo: Double, hi: Double): Double = math.max(lo, math.min(hi, v))
  def round1(v: Double): Double = StrictMath.round(v * 10) / 10.0
