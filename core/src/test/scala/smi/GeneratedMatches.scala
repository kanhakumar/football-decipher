package smi

import smi.generator.*
import smi.model.*

/** Generated matches shared by the generator suites, built once per test run. */
object GeneratedMatches:
  val Seeds: Vector[Long] = (1L to 50L).toVector

  final case class Match(seed: Long, envelopes: Vector[Envelope]):
    lazy val events: Vector[Event] = MatchSummary.events(envelopes)
    lazy val summary: MatchSummary = MatchSummary.of(envelopes)
    lazy val lineups: Vector[Lineup] =
      envelopes.collect { case Envelope(_, _, _, _, _, _, _, Payload.Team(l)) => l }

  def generate(seed: Long, scenario: Scenario = Scenario.default): Match =
    Match(seed, MatchGenerator.generate(GeneratorConfig(seed, scenario)))

  lazy val default: Vector[Match] = Seeds.map(generate(_))

  /** The default scenario with its story beats removed, as a control group. */
  lazy val noBeats: Vector[Match] =
    val control = Scenario.default.copy(name = "no-beats", beats = None)
    Seeds.map(generate(_, control))
