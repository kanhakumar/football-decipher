package smi.insight

import smi.model.*

/** How a possession began and how it went, from events alone. */
final case class PossessionStory(
    change: Option[Event],
    events: Vector[Event],
    shot: Event
):
  def first: Event = events.headOption.getOrElse(shot)
  def startClockS: Double = change.fold(first.periodClockS)(_.periodClockS)
  def durationS: Double = shot.periodClockS - startClockS
  def completedPasses: Int =
    events.count(e =>
      e.`type` == EventType.Pass && e.outcome.contains(Outcome.Complete) && e != shot
    )
  def wonBy: Option[PossessionCause] = change.flatMap(_.details.collect {
    case p: PossessionChangeDetails => p.cause
  })
  def startX: Double = first.x.getOrElse(52.5)

  /** Ball won in the team's own half by a tackle or interception, shot within 15 s and 5 passes. */
  def isCounter: Boolean =
    wonBy.exists(c => c == PossessionCause.Tackle || c == PossessionCause.Interception) &&
      startX < 52.5 && durationS <= 15 && completedPasses <= 5

object Possessions:
  /** The shooting team's events in the shot's possession, and the change that started it. */
  def story(ordered: Vector[Event], shot: Event): PossessionStory =
    val own = ordered
      .filter(e =>
        e.possessionId == shot.possessionId && e.teamId == shot.teamId && e.period == shot.period
      )
      .takeWhile(_ != shot) :+ shot
    val firstIndex = ordered.indexOf(own.head)
    // Walk back from the possession's first event to the possession_change that handed it over.
    val change = ordered
      .take(firstIndex)
      .reverseIterator
      .takeWhile(e =>
        e.period == shot.period && (e.possessionId == shot.possessionId || e.`type` == EventType.PossessionChange)
      )
      .find(e =>
        e.details.exists {
          case p: PossessionChangeDetails => p.toTeam == shot.teamId
          case _                          => false
        }
      )
    PossessionStory(change, own, shot)

  /** The same story rebuilt from an insight's evidence events only. */
  def fromEvidence(evidence: Vector[Event], shot: Event): PossessionStory =
    val own = evidence.filter(e =>
      e.possessionId == shot.possessionId && e.teamId == shot.teamId && e != shot
    ) :+ shot
    val change = evidence.find(e =>
      e.details.exists {
        case p: PossessionChangeDetails => p.toTeam == shot.teamId
        case _                          => false
      }
    )
    PossessionStory(change, own, shot)
