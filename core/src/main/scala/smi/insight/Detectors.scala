package smi.insight

import io.circe.JsonObject
import smi.engine.{Engine, EngineState, Enrich, Holder, Window, Zones}
import smi.model.*

/** The moment detectors (ADR-011). Thresholds are tuned on 50 seeds of the default scenario. */
object Detectors:
  import F.*

  val all: Vector[Detector] =
    Vector(Goal, RedCard, KeyChance, MomentumSwing, PressureShift, ControlChaos, Rhythm, Milestone)
  val byKind: Map[InsightKind, Detector] = all.map(d => d.kind -> d).toMap

  private def goalsBefore(state: EngineState, e: Event): Vector[Event] =
    upTo(state, e).filter(isGoal)

  /** Goals for and against `team` among the evidence. */
  private def score(evidence: Vector[Event], team: TeamId): (Int, Int) =
    val goals = evidence.filter(isGoal)
    (goals.count(_.teamId == team), goals.count(_.teamId != team))

  // ---------------------------------------------------------------------------------------------

  object Goal extends Detector:
    val kind = InsightKind.Goal

    override def onEvent(state: EngineState, e: Event): Option[Candidate] =
      Option.when(isGoal(e)) {
        val story = Possessions.story(events(state), e)
        val evidence = (goalsBefore(state, e) ++ story.events ++ story.change).distinct.sortBy(x =>
          (x.period, x.periodClockS, x.eventId.value)
        )
        Candidate(
          Header(kind, e.period, List(e.teamId, opponentOf(state, e.teamId)), e.playerId.toList),
          evidence.map(_.eventId),
          e,
          s => s.events.get(e.eventId).flatMap(x => onEvent(s, x.event))
        )
      }

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      val shot = evidence.filter(isGoal).last
      val story = Possessions.fromEvidence(evidence, shot)
      val (forGoals, against) = score(evidence, h.team)
      val xg = Enrich(shot).xg.getOrElse(0.0)
      obj(
        "minute" -> int(minute(shot)),
        "team_goals" -> int(forGoals),
        "opponent_goals" -> int(against),
        "xg" -> num(xg),
        "distance_m" -> num(shot.x.zip(shot.y).map(Zones.distanceToGoal).getOrElse(0.0), 1),
        "possession_passes" -> int(story.completedPasses),
        "possession_duration_s" -> num(story.durationS, 1),
        "won_ball_by" -> str(story.wonBy.fold("none")(PossessionCause.wire)),
        "counter_attack" -> bool(story.isCounter)
      )

    def why(f: JsonObject): WhyItMatters =
      val (tg, og) = (intOf(f, "team_goals"), intOf(f, "opponent_goals"))
      val code =
        if boolOf(f, "counter_attack") then "counter_attack_goal"
        else if tg == og then "equaliser"
        else if tg == og + 1 then "takes_lead"
        else if tg > og then "extends_lead"
        else "pulls_one_back"
      val extra =
        if code == "counter_attack_goal" then Seq("possession_duration_s", "possession_passes")
        else Seq("xg")
      WhyItMatters(
        ReasonCode.unsafe(code),
        params(f, (Seq("team_goals", "opponent_goals") ++ extra)*)
      )

    def importance(f: JsonObject): Double =
      val (tg, og) = (intOf(f, "team_goals"), intOf(f, "opponent_goals"))
      val swing = if tg == og || tg == og + 1 then 0.05 else 0.0
      val late = if intOf(f, "minute") >= 75 then 0.03 else 0.0
      round(math.min(1.0, 0.88 + swing + late), 3)

  // ---------------------------------------------------------------------------------------------

  object RedCard extends Detector:
    val kind = InsightKind.RedCard

    override def onEvent(state: EngineState, e: Event): Option[Candidate] =
      Option.when(card(e).contains(Card.Red)) {
        val before = upTo(state, e)
        val bookings = before.filter(x => x.playerId == e.playerId && card(x).contains(Card.Yellow))
        val reds = before.filter(x => x.teamId == e.teamId && card(x).contains(Card.Red))
        val evidence = (before.filter(isGoal) ++ bookings ++ reds).distinct.sortBy(x =>
          (x.period, x.periodClockS, x.eventId.value)
        )
        Candidate(
          Header(kind, e.period, List(e.teamId, opponentOf(state, e.teamId)), e.playerId.toList),
          evidence.map(_.eventId),
          e,
          s => s.events.get(e.eventId).flatMap(x => onEvent(s, x.event))
        )
      }

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      val reds = evidence.filter(x => x.teamId == h.team && card(x).contains(Card.Red))
      val red = reds.filter(_.playerId == h.players.headOption).last
      val (forGoals, against) = score(evidence, h.team)
      obj(
        "minute" -> int(minute(red)),
        "second_yellow" -> bool(
          evidence.exists(x => x.playerId == red.playerId && card(x).contains(Card.Yellow))
        ),
        "players_left" -> int(11 - reds.size),
        "team_goals" -> int(forGoals),
        "opponent_goals" -> int(against)
      )

    def why(f: JsonObject): WhyItMatters =
      val code = if intOf(f, "players_left") >= 10 then "down_to_ten" else "down_to_nine"
      WhyItMatters(
        ReasonCode.unsafe(code),
        params(f, "players_left", "minute", "team_goals", "opponent_goals")
      )

    def importance(f: JsonObject): Double =
      val level = if intOf(f, "team_goals") == intOf(f, "opponent_goals") then 0.05 else 0.0
      round(0.85 + level, 3)

  // ---------------------------------------------------------------------------------------------

  object KeyChance extends Detector:
    val kind = InsightKind.KeyChance
    val MinXg = 0.25

    private def xgOf(e: Event): Double = Enrich(e).xg.getOrElse(0.0)

    override def onEvent(state: EngineState, e: Event): Option[Candidate] =
      Option.when(e.`type` == EventType.Shot && !isGoal(e) && xgOf(e) >= MinXg) {
        val assist = e.details
          .collect { case s: ShotDetails => s.assistEventId }
          .flatten
          .flatMap(state.events.get)
          .map(_.event)
        Candidate(
          Header(kind, e.period, List(e.teamId), e.playerId.toList),
          (assist.toVector :+ e).map(_.eventId),
          e,
          s => s.events.get(e.eventId).flatMap(x => onEvent(s, x.event))
        )
      }

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      val shot = evidence.filter(_.`type` == EventType.Shot).last
      obj(
        "minute" -> int(minute(shot)),
        "xg" -> num(xgOf(shot)),
        "outcome" -> str(shot.outcome.fold("unknown")(Outcome.wire)),
        "distance_m" -> num(shot.x.zip(shot.y).map(Zones.distanceToGoal).getOrElse(0.0), 1),
        "assisted" -> bool(evidence.exists(_.`type` == EventType.Pass))
      )

    def why(f: JsonObject): WhyItMatters =
      val code = if strOf(f, "outcome") == "saved" then "big_chance_saved" else "big_chance_missed"
      WhyItMatters(ReasonCode.unsafe(code), params(f, "xg", "distance_m"))

    def importance(f: JsonObject): Double = round(math.min(0.75, 0.45 + 0.4 * double(f, "xg")), 3)

  // ---------------------------------------------------------------------------------------------

  /** Shared shape of the window detectors: an `after` window ending now and a `before` window. */
  abstract class Windowed(beforeMinutes: Int, afterMinutes: Int, minBefore: Int) extends Detector:
    override val windowKeys = List("before_from_minute", "before_to_minute", "after_to_minute")

    /** The before window may be cut short by the start of the period, down to `minBefore`. */
    protected def windows(period: Int, minute: Int): Option[Map[String, Double]] =
      val start = math.max(Engine.baseMinute(period), minute - afterMinutes - beforeMinutes)
      Option.when(minute - afterMinutes - start >= minBefore)(
        Map(
          "before_from_minute" -> start.toDouble,
          "before_to_minute" -> (minute - afterMinutes).toDouble,
          "after_to_minute" -> minute.toDouble
        )
      )

    protected def before(h: Header): Window = h.window("before_from_minute", "before_to_minute")
    protected def after(h: Header): Window = h.window("before_to_minute", "after_to_minute")

    /** Builds a candidate whose evidence is every event the facts count. */
    protected def candidate(
        state: EngineState,
        h: Header,
        counted: Event => Boolean,
        recheck: EngineState => Option[Candidate],
        key: String
    ): Option[Candidate] =
      val evidence =
        events(state).filter(e => (before(h).contains(e) || after(h).contains(e)) && counted(e))
      evidence.lastOption.map(last =>
        Candidate(h, evidence.map(_.eventId), last, recheck, Some(key))
      )

  // ---------------------------------------------------------------------------------------------

  object MomentumSwing extends Windowed(10, 10, 10):
    val kind = InsightKind.MomentumSwing
    private def attacking(e: Event) =
      e.`type` == EventType.Shot || (e.`type` == EventType.Pass && e.x.exists(
        _ >= Zones.AttackingThirdStart
      ))

    override def onMinute(state: EngineState, period: Int, minute: Int): Vector[Candidate] =
      windows(period, minute).toVector.flatMap { w =>
        state.teams.flatMap { t =>
          val h = Header(kind, period, List(t, opponentOf(state, t)), windows = w)
          val c = candidate(
            state,
            h,
            attacking,
            s => onMinute(s, period, minute).find(_.header.team == t),
            s"$kind:${t.value}"
          )
          c.filter(c => fires(derive(h, c.evidence.map(state.events(_).event))))
        }
      }

    private def fires(f: JsonObject) =
      intOf(f, "before_total_actions") >= 8 && intOf(f, "after_total_actions") >= 8 &&
        double(f, "before_share") <= 0.40 && double(f, "after_share") >= 0.60

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      def share(w: Window) =
        val in = evidence.filter(w.contains)
        val own = in.count(_.teamId == h.team)
        (own, in.size, if in.isEmpty then 0.0 else own.toDouble / in.size)
      val (bOwn, bAll, bShare) = share(before(h))
      val (aOwn, aAll, aShare) = share(after(h))
      val shots = evidence.filter(e =>
        after(h).contains(e) && e.teamId == h.team && e.`type` == EventType.Shot
      )
      obj(
        "before_team_actions" -> int(bOwn),
        "before_total_actions" -> int(bAll),
        "before_share" -> num(bShare),
        "after_team_actions" -> int(aOwn),
        "after_total_actions" -> int(aAll),
        "after_share" -> num(aShare),
        "after_shots" -> int(shots.size),
        "after_xg" -> num(shots.map(Enrich(_).xg.getOrElse(0.0)).sum)
      )

    def why(f: JsonObject): WhyItMatters =
      WhyItMatters(
        ReasonCode.unsafe("momentum_swings_to_team"),
        params(f, "before_share", "after_share", "after_shots")
      )

    def importance(f: JsonObject): Double =
      round(math.min(0.7, 0.5 + 0.3 * (double(f, "after_share") - double(f, "before_share"))), 3)

  // ---------------------------------------------------------------------------------------------

  object PressureShift extends Windowed(15, 6, 8):
    val kind = InsightKind.PressureShift
    val Ratio = 1.5

    private def counted(h: Header)(e: Event) =
      (e.teamId == h.team && e.`type` == EventType.Pressure) ||
        (e.teamId != h.team && e.`type` == EventType.Pass) ||
        (e.teamId == h.team && highWin(e)) ||
        (e.teamId == h.team && e.`type` == EventType.Shot)

    /** A tackle won or an interception in the opponent's half. */
    private def highWin(e: Event) =
      e.x.exists(_ >= Zones.Halfway) &&
        (e.`type` == EventType.Interception || (e.`type` == EventType.Tackle && e.outcome.contains(
          Outcome.Won
        )))

    override def onMinute(state: EngineState, period: Int, minute: Int): Vector[Candidate] =
      windows(period, minute).toVector.flatMap { w =>
        state.teams.flatMap { t =>
          val h = Header(kind, period, List(t, opponentOf(state, t)), windows = w)
          val c = candidate(
            state,
            h,
            counted(h),
            s => onMinute(s, period, minute).find(_.header.team == t),
            s"$kind:${t.value}"
          )
          c.filter(c => fires(derive(h, c.evidence.map(state.events(_).event))))
        }
      }

    private def fires(f: JsonObject) =
      intOf(f, "after_pressures") >= 10 && intOf(f, "before_opponent_passes") >= 20 &&
        intOf(f, "after_opponent_passes") >= 8 && double(f, "before_rate") > 0 &&
        double(f, "after_rate") >= Ratio * double(f, "before_rate")

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      def rate(w: Window) =
        val in = evidence.filter(w.contains)
        val pressures = in.count(e => e.teamId == h.team && e.`type` == EventType.Pressure)
        val passes = in.count(e => e.teamId != h.team && e.`type` == EventType.Pass)
        (pressures, passes, if passes == 0 then 0.0 else pressures.toDouble / passes)
      val (bP, bO, bR) = rate(before(h))
      val (aP, aO, aR) = rate(after(h))
      val now = evidence.filter(e => after(h).contains(e) && e.teamId == h.team)
      obj(
        "before_pressures" -> int(bP),
        "before_opponent_passes" -> int(bO),
        "before_rate" -> num(bR),
        "after_pressures" -> int(aP),
        "after_opponent_passes" -> int(aO),
        "after_rate" -> num(aR),
        "after_ball_wins_high" -> int(now.count(highWin)),
        "after_shots" -> int(now.count(_.`type` == EventType.Shot))
      )

    def why(f: JsonObject): WhyItMatters =
      if intOf(f, "after_shots") >= 2 || intOf(f, "after_ball_wins_high") >= 3 then
        WhyItMatters(
          ReasonCode.unsafe("pressing_raises_chance_quality"),
          params(f, "before_rate", "after_rate", "after_ball_wins_high", "after_shots")
        )
      else
        WhyItMatters(ReasonCode.unsafe("high_press_spell"), params(f, "before_rate", "after_rate"))

    def importance(f: JsonObject): Double =
      val lift = double(f, "after_rate") / math.max(double(f, "before_rate"), 1e-9)
      round(math.min(0.75, 0.5 + 0.05 * lift + 0.02 * intOf(f, "after_shots")), 3)

  // ---------------------------------------------------------------------------------------------

  object ControlChaos extends Windowed(10, 10, 10):
    val kind = InsightKind.ControlVsChaos

    private def counted(e: Event) =
      e.`type` == EventType.PossessionChange || e.`type` == EventType.Pass

    /** Chaos is reported once for the game (home team first in `teams`); control per team. */
    override def onMinute(state: EngineState, period: Int, minute: Int): Vector[Candidate] =
      windows(period, minute).toVector.flatMap { w =>
        def at(t: TeamId, key: String) =
          val h = Header(kind, period, List(t, opponentOf(state, t)), windows = w)
          candidate(
            state,
            h,
            counted,
            s => onMinute(s, period, minute).find(_.cooldownKey.contains(key)),
            key
          )
            .map(c => c -> mode(derive(h, c.evidence.map(state.events(_).event))))
        val chaos = at(state.teams.head, s"$kind:chaos").collect {
          case (c, Some("game_turns_chaotic")) => c
        }
        if chaos.isDefined then chaos.toVector
        else
          state.teams.flatMap(t =>
            at(t, s"$kind:${t.value}").collect { case (c, Some("team_takes_control")) => c }
          )
      }

    /** Chaos is about both teams, control about one; returns the reason code if either holds. */
    private def mode(f: JsonObject): Option[String] =
      val chaos = intOf(f, "after_possession_changes") >= 26 &&
        intOf(f, "after_possession_changes") >= 1.35 * math.max(
          1,
          intOf(f, "before_possession_changes")
        )
      val control =
        double(f, "after_team_pass_share") >= 0.65 && double(f, "before_team_pass_share") <= 0.55 &&
          intOf(f, "after_team_passes") >= 50
      if chaos then Some("game_turns_chaotic")
      else if control then Some("team_takes_control")
      else None

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      def window(w: Window) =
        val in = evidence.filter(w.contains)
        val changes = in.count(_.`type` == EventType.PossessionChange)
        val passes = in.filter(_.`type` == EventType.Pass)
        val own = passes.count(_.teamId == h.team)
        (changes, passes.size, own, if passes.isEmpty then 0.0 else own.toDouble / passes.size)
      val (bC, bP, bOwn, bShare) = window(before(h))
      val (aC, aP, aOwn, aShare) = window(after(h))
      obj(
        "before_possession_changes" -> int(bC),
        "before_passes" -> int(bP),
        "before_team_passes" -> int(bOwn),
        "before_team_pass_share" -> num(bShare),
        "after_possession_changes" -> int(aC),
        "after_passes" -> int(aP),
        "after_team_passes" -> int(aOwn),
        "after_team_pass_share" -> num(aShare),
        "after_passes_per_possession" -> num(aP.toDouble / math.max(1, aC), 2)
      )

    def why(f: JsonObject): WhyItMatters =
      mode(f) match
        case Some("game_turns_chaotic") =>
          WhyItMatters(
            ReasonCode.unsafe("game_turns_chaotic"),
            params(
              f,
              "before_possession_changes",
              "after_possession_changes",
              "after_passes_per_possession"
            )
          )
        case _ =>
          WhyItMatters(
            ReasonCode.unsafe("team_takes_control"),
            params(f, "before_team_pass_share", "after_team_pass_share", "after_team_passes")
          )

    def importance(f: JsonObject): Double = 0.45

  // ---------------------------------------------------------------------------------------------

  object Rhythm extends Windowed(10, 10, 10):
    val kind = InsightKind.RhythmChange
    val Change = 0.35

    private def counted(e: Event) = e.`type` == EventType.Pass

    /** Tempo is about the whole game: one insight, both teams in `teams`. */
    override def onMinute(state: EngineState, period: Int, minute: Int): Vector[Candidate] =
      windows(period, minute).toVector.flatMap { w =>
        val h = Header(kind, period, state.teams.toList, windows = w)
        candidate(state, h, counted, s => onMinute(s, period, minute).headOption, s"$kind")
          .filter(c => fires(derive(h, c.evidence.map(state.events(_).event))))
      }

    private def fires(f: JsonObject) =
      intOf(f, "before_passes") >= 60 &&
        math.abs(double(f, "after_per_minute") / double(f, "before_per_minute") - 1) >= Change

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      val b = evidence.count(before(h).contains)
      val a = evidence.count(after(h).contains)
      val beforeMinutes = h.windows("before_to_minute") - h.windows("before_from_minute")
      val afterMinutes = h.windows("after_to_minute") - h.windows("before_to_minute")
      obj(
        "before_passes" -> int(b),
        "after_passes" -> int(a),
        "before_per_minute" -> num(b / beforeMinutes, 1),
        "after_per_minute" -> num(a / afterMinutes, 1)
      )

    def why(f: JsonObject): WhyItMatters =
      val code =
        if double(f, "after_per_minute") > double(f, "before_per_minute") then "tempo_rises"
        else "tempo_drops"
      WhyItMatters(ReasonCode.unsafe(code), params(f, "before_per_minute", "after_per_minute"))

    def importance(f: JsonObject): Double = 0.4

  // ---------------------------------------------------------------------------------------------

  object Milestone extends Detector:
    val kind = InsightKind.Milestone
    val PassMarks = Set(50, 75, 100)
    val ShotMarks = Set(10, 20)

    override def onEvent(state: EngineState, e: Event): Option[Candidate] =
      def make(h: Header, ev: Vector[Event]) =
        Candidate(
          h,
          ev.map(_.eventId),
          e,
          s => s.events.get(e.eventId).flatMap(x => onEvent(s, x.event))
        )
      // The engine's running totals are cheap; only scan the event list when a mark is reachable.
      def total(metric: String, holder: Holder) =
        Engine.query(state, metric, holder).value.getOrElse(0.0).toInt
      def reached(marks: Set[Int], metric: String, holder: Holder) =
        total(metric, holder) >= marks.min
      val completed = e.`type` == EventType.Pass && e.outcome.contains(Outcome.Complete)
      e.playerId match
        case Some(p) if completed && reached(PassMarks, "passes_completed", Holder.Player(p)) =>
          val passes = upTo(state, e).filter(x =>
            x.playerId == e.playerId && x.`type` == EventType.Pass && x.outcome.contains(
              Outcome.Complete
            )
          )
          Option.when(PassMarks(passes.size))(
            make(Header(kind, e.period, List(e.teamId), List(p)), passes)
          )
        case Some(p) if isGoal(e) && reached(Set(2), "goals", Holder.Player(p)) =>
          val goals = upTo(state, e).filter(x => isGoal(x) && x.playerId == e.playerId)
          Option.when(goals.size >= 2)(make(Header(kind, e.period, List(e.teamId), List(p)), goals))
        case _
            if e.`type` == EventType.Shot && !isGoal(e) && reached(
              ShotMarks,
              "shots",
              Holder.Team(e.teamId)
            ) =>
          teamShots(state, e).map(shots => make(Header(kind, e.period, List(e.teamId)), shots))
        case _ if isGoal(e) && reached(ShotMarks, "shots", Holder.Team(e.teamId)) =>
          teamShots(state, e).map(shots => make(Header(kind, e.period, List(e.teamId)), shots))
        case _ => None

    private def teamShots(state: EngineState, e: Event): Option[Vector[Event]] =
      val shots = upTo(state, e).filter(x => x.teamId == e.teamId && x.`type` == EventType.Shot)
      Option.when(ShotMarks(shots.size))(shots)

    def derive(h: Header, evidence: Vector[Event]): JsonObject =
      val what =
        if evidence.forall(_.`type` == EventType.Pass) then "completed_passes"
        else if evidence.forall(isGoal) then "goals"
        else "shots"
      obj(
        "milestone" -> str(what),
        "count" -> int(evidence.size),
        "minute" -> int(minute(evidence.last))
      )

    def why(f: JsonObject): WhyItMatters =
      val code = strOf(f, "milestone") match
        case "completed_passes" => "player_pass_milestone"
        case "goals"            => if intOf(f, "count") >= 3 then "hat_trick" else "brace"
        case _                  => "team_shot_milestone"
      WhyItMatters(ReasonCode.unsafe(code), params(f, "count", "minute"))

    def importance(f: JsonObject): Double = strOf(f, "milestone") match
      case "goals" => if intOf(f, "count") >= 3 then 0.7 else 0.5
      case _       => 0.3
