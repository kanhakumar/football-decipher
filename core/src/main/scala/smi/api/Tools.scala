package smi.api

import scala.collection.immutable.ListMap
import smi.engine.{Engine, EngineState, Holder, Metrics, Window}
import smi.model.*

/** What the tool endpoints answer, computed from the live engine (the generator's side of the
  * viewer delay, so agents can explain a moment before viewers see it). Small, capped answers:
  * agents never bulk-read events or frames (rule 4, D9).
  */
object Tools:
  val MaxEvents = 50
  val EvidenceSample = 20
  val TeamMetrics = List(
    "goals",
    "shots",
    "shots_on_target",
    "xg",
    "possession_share",
    "pass_completion",
    "field_tilt",
    "ppda"
  )
  val PlayerMetrics = List(
    "passes",
    "passes_completed",
    "shots",
    "xg",
    "progressive_passes",
    "pressures",
    "tackles_won",
    "interceptions"
  )
  val WorkloadMetrics =
    List("distance_covered", "high_speed_distance", "top_speed", "sprints", "fatigue_index")

  /** Query window: explicit bounds, or the last N minutes of the current period. */
  final case class WindowQuery(
      period: Option[Int] = None,
      fromMinute: Option[Double] = None,
      toMinute: Option[Double] = None,
      lastMinutes: Option[Double] = None
  )

  private def engine(p: Playback): EngineState = p.moments.engine

  private def lastEvent(p: Playback, upTo: Int): Option[Event] =
    p.feed.take(upTo).reverseIterator.collectFirst {
      case Envelope(_, _, _, _, _, _, _, Payload.Ev(e)) => e
    }

  def window(p: Playback, q: WindowQuery): (Window, WindowView) =
    q.lastMinutes match
      case Some(n) =>
        val now = lastEvent(p, p.live)
        val period = now.map(_.period).getOrElse(1)
        val minute = now.map(e => Engine.baseMinute(e.period) + e.periodClockS / 60).getOrElse(0.0)
        val w = Window(Some(period), math.floor(minute - n + 1), math.floor(minute) + 1)
        (w, WindowView(w.period, Some(w.fromMinute), Some(w.toMinute)))
      case None =>
        val w = Window(q.period, q.fromMinute.getOrElse(0), q.toMinute.getOrElse(Double.MaxValue))
        (w, WindowView(q.period, q.fromMinute, q.toMinute))

  def state(p: Playback): MatchStateView =
    val e = engine(p)
    val d = e.derived
    MatchStateView(
      status = Status.wire(p.status),
      seed = Option.when(p.generation > 0)(p.config.seed),
      speed = p.config.speed,
      delayS = p.config.delayS,
      tracking = p.config.tracking,
      live = lastEvent(p, p.live).map(ClockView.of),
      viewer = lastEvent(p, p.viewer).map(ClockView.of),
      score = ListMap.from(e.teams.map(t => t.value -> d.score.getOrElse(t, 0))),
      possessionTeam = d.possessionTeam,
      playersOnPitch =
        ListMap.from(e.teams.map(t => t.value -> d.onPitch.getOrElse(t, Set.empty).size)),
      envelopesTotal = Option.when(p.ended)(p.feed.size),
      envelopesLive = p.live,
      envelopesViewer = p.viewer,
      insights = p.moments.active.size,
      overlays = p.overlays.size,
      runId = Option.when(p.generation > 0)(p.runId),
      feed = if p.source == FeedSource.Bus then "bus" else "replay"
    )

  def info(p: Playback): MatchInfoView =
    val e = engine(p)
    MatchInfoView(p.matchId, e.teams.toList.flatMap(e.lineups.get))

  private def metrics(ids: List[String], forPlayer: Boolean): Either[String, List[String]] =
    ids.find(id => !Metrics.byId.contains(id)) match
      case Some(bad) => Left(s"unknown metric '$bad'")
      case None =>
        ids.find(id => forPlayer && Metrics(id).teamOnly) match
          case Some(team) => Left(s"'$team' is a team metric")
          case None       => Right(ids)

  private def team(p: Playback, id: String): Either[String, TeamId] =
    TeamId.from(id).filterOrElse(engine(p).teams.contains, s"unknown team '$id'")

  private def player(p: Playback, id: String): Either[String, PlayerId] =
    PlayerId
      .from(id)
      .filterOrElse(
        pid =>
          engine(p).lineups.values.exists(l => (l.starters ++ l.bench).exists(_.playerId == pid)),
        s"unknown player '$id'"
      )

  def teamStats(
      p: Playback,
      teamId: String,
      ids: List[String],
      q: WindowQuery
  ): Either[String, StatsView] =
    for
      t <- team(p, teamId)
      ms <- metrics(if ids.isEmpty then TeamMetrics else ids, forPlayer = false)
      (w, view) = window(p, q)
    yield StatsView(t.value, view, ms.map(m => MetricView.of(Engine.team(engine(p), m, t, w))))

  def playerStats(
      p: Playback,
      playerId: String,
      ids: List[String],
      q: WindowQuery
  ): Either[String, StatsView] =
    for
      pl <- player(p, playerId)
      ms <- metrics(if ids.isEmpty then PlayerMetrics else ids, forPlayer = true)
      (w, view) = window(p, q)
    yield StatsView(pl.value, view, ms.map(m => MetricView.of(Engine.player(engine(p), m, pl, w))))

  def workload(p: Playback, playerId: String, q: WindowQuery): Either[String, StatsView] =
    playerStats(p, playerId, WorkloadMetrics, q)

  def shape(p: Playback, teamId: String, q: WindowQuery): Either[String, ShapeView] =
    team(p, teamId).map { t =>
      val (w, view) = window(p, q)
      val (positions, basis) = Engine.shape(engine(p), t, w)
      val names = engine(p).lineups
        .get(t)
        .toList
        .flatMap(l => l.starters ++ l.bench)
        .map(pl => pl.playerId -> pl.name)
        .toMap
      def r1(v: Double) = StrictMath.round(v * 10) / 10.0
      ShapeView(
        t,
        view,
        basis,
        positions.toList.map(a =>
          PositionView(a.player, names.get(a.player), r1(a.x), r1(a.y), a.samples)
        )
      )
    }

  def recentEvents(p: Playback, n: Int, teamId: Option[String]): Either[String, EventsView] =
    for
      t <- teamId.fold(Right(None): Either[String, Option[TeamId]])(id => team(p, id).map(Some(_)))
      limit = math.max(1, math.min(n, MaxEvents))
    yield
      val events =
        engine(p).ordered.map(_.event).filter(e => t.forall(_ == e.teamId)).takeRight(limit)
      EventsView(events.size, events.map(EventView.of).toList)

  def insights(
      p: Playback,
      minImportance: Double,
      kind: Option[String],
      all: Boolean
  ): Either[String, InsightsView] =
    kind
      .fold(Right(None): Either[String, Option[InsightKind]])(k =>
        InsightKind.fromWire(k).map(Some(_))
      )
      .map { k =>
        val pool = if all then p.insights else p.moments.ranked
        val picked = pool.filter(i => i.importance >= minImportance && k.forall(_ == i.kind))
        InsightsView(picked.size, picked.toList)
      }

  /** The latest version of an insight with a compact summary of its evidence. */
  def insightDetail(p: Playback, id: String): Either[String, InsightDetail] =
    for
      iid <- InsightId.from(id)
      insight <- p.insights.findLast(_.insightId == iid).toRight(s"unknown insight '$id'")
    yield
      val evidence = insight.evidenceEventIds.flatMap(engine(p).events.get).map(_.event)
      val byType =
        evidence.groupMapReduce(e => EventType.wire(e.`type`))(_ => 1)(_ + _).toList.sortBy(-_._2)
      InsightDetail(
        insight,
        EvidenceSummary(
          insight.evidenceEventIds.size,
          ListMap.from(byType),
          evidence.takeRight(EvidenceSample).map(EventView.of)
        )
      )

  def holderOf(p: Playback, id: String): Option[Holder] =
    team(p, id).toOption.map(Holder.Team(_)).orElse(player(p, id).toOption.map(Holder.Player(_)))
