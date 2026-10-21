package smi

/** SplitMix64. The only source of randomness in the generator: one instance per match, seeded from
  * the CLI. Math goes through StrictMath so output is identical on every JVM and CPU.
  */
final class Rng(seed: Long):
  private var state = seed

  def nextLong(): Long =
    state += 0x9e3779b97f4a7c15L
    var z = state
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

  /** Uniform in [0, 1). */
  def uniform(): Double = (nextLong() >>> 11) * Unit53

  private val Unit53 = 1.0 / (1L << 53)

  def between(lo: Double, hi: Double): Double = lo + (hi - lo) * uniform()

  def chance(p: Double): Boolean = uniform() < p

  /** Standard normal via Box-Muller. */
  def gaussian(): Double =
    val u1 = math.max(uniform(), 1e-12)
    val u2 = uniform()
    StrictMath.sqrt(-2 * StrictMath.log(u1)) * StrictMath.cos(2 * math.Pi * u2)

  def weighted[A](options: Seq[(A, Double)]): A =
    val total = options.map(_._2).sum
    val target = uniform() * total
    options
      .scanLeft((Option.empty[A], 0.0)) { case ((_, acc), (a, w)) => (Some(a), acc + w) }
      .collectFirst { case (Some(a), acc) if acc > target => a }
      .getOrElse(options.last._1)
