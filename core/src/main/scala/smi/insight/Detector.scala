package smi.insight

import io.circe.{Json, JsonObject}
import scala.annotation.unused
import scala.math.Ordering.Implicits.infixOrderingOps
import smi.engine.{Engine, EngineState, Window}
import smi.model.*

/** What `derive` may use besides the evidence events. Window bounds live in the insight's facts
  * (under the detector's `windowKeys`), so the header can be rebuilt from a published insight.
  */
final case class Header(
    kind: InsightKind,
    period: Int,
    teams: List[TeamId],
    players: List[PlayerId] = Nil,
    windows: Map[String, Double] = Map.empty
):
  def team: TeamId = teams.head
  def window(from: String, to: String): Window = Window(Some(period), windows(from), windows(to))

/** A detection, before it is numbered and published. `recheck` re-runs the detection on a corrected
  * engine state (empty if it no longer holds).
  */
final case class Candidate(
    header: Header,
    evidence: Vector[EventId],
    trigger: Event,
    recheck: EngineState => Option[Candidate],
    cooldownKey: Option[String] = None
)

/** A moment detector (ADR-011). `derive`, `why` and `importance` must use nothing but their
  * arguments, so any published insight can be re-derived from its evidence events.
  */
trait Detector:
  def kind: InsightKind
  def windowKeys: List[String] = Nil

  /** Whether the insight carries the match context (score and players on the pitch, `Context`). */
  def context: Boolean = true

  /** At most one active insight per match (the full-time recap). */
  def once: Boolean = false

  /** Event-triggered detection, run for each new event. */
  def onEvent(@unused state: EngineState, @unused e: Event): Option[Candidate] = None

  /** Window detection, run when display minute `minute` has just completed in `period`. */
  def onMinute(
      @unused state: EngineState,
      @unused period: Int,
      @unused minute: Int
  ): Vector[Candidate] =
    Vector.empty

  def derive(h: Header, evidence: Vector[Event]): JsonObject
  def why(facts: JsonObject): WhyItMatters
  def importance(facts: JsonObject): Double

/** Small helpers shared by the detectors. */
object F:
  def displayMinute(e: Event): Double = Engine.baseMinute(e.period) + e.periodClockS / 60

  /** Broadcast minute: an event at 33:12 happens in the 34th minute. */
  def minute(e: Event): Int = Engine.baseMinute(e.period) + (e.periodClockS / 60).toInt + 1

  def round(v: Double, places: Int): Double =
    val f = StrictMath.pow(10, places)
    StrictMath.round(v * f) / f

  def num(v: Double, places: Int = 3): Json = Json.fromDoubleOrNull(round(v, places))
  def int(v: Int): Json = Json.fromInt(v)
  def bool(v: Boolean): Json = Json.fromBoolean(v)
  def str(v: String): Json = Json.fromString(v)

  def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  def double(facts: JsonObject, key: String): Double =
    facts(key)
      .flatMap(_.asNumber)
      .map(_.toDouble)
      .getOrElse(throw java.util.NoSuchElementException(s"fact $key"))
  def intOf(facts: JsonObject, key: String): Int = double(facts, key).toInt
  def boolOf(facts: JsonObject, key: String): Boolean =
    facts(key).flatMap(_.asBoolean).getOrElse(false)
  def strOf(facts: JsonObject, key: String): String = facts(key).flatMap(_.asString).getOrElse("")

  /** Params for a reason code: the named facts, copied so every number in params is a fact. */
  def params(facts: JsonObject, keys: String*): JsonObject =
    JsonObject.fromIterable(keys.flatMap(k => facts(k).map(k -> _)))

  def events(state: EngineState): Vector[Event] = state.ordered.map(_.event)

  /** Events in match order up to and including `e`. */
  def upTo(state: EngineState, e: Event): Vector[Event] =
    val key = (e.period, e.periodClockS, e.eventId.value)
    events(state).takeWhile(x => (x.period, x.periodClockS, x.eventId.value) <= key)

  def isGoal(e: Event): Boolean = e.`type` == EventType.Shot && e.outcome.contains(Outcome.Goal)

  def card(e: Event): Option[Card] = e.details.collect { case f: FoulDetails => f.card }

  def isRed(e: Event): Boolean = card(e).contains(Card.Red)

  def inMatchOrder(es: Vector[Event]): Vector[Event] =
    es.distinct.sortBy(x => (x.period, x.periodClockS, x.eventId.value))

  /** Evidence plus the goals and red cards up to `at`, so the context can be re-derived. */
  def withContext(state: EngineState, at: Event, evidence: Vector[Event]): Vector[Event] =
    inMatchOrder(evidence ++ upTo(state, at).filter(x => isGoal(x) || isRed(x)))

  def opponentOf(state: EngineState, team: TeamId): TeamId = state.opponent(team).get
