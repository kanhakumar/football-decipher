package smi.model

// Wire strings are the snake_case form of the case names unless `toWire` is overridden.

enum Source:
  case Synthetic, Replay, ProviderAdapter
object Source extends WireEnum[Source]("source"):
  def all = values

enum Op:
  case Create, Update, Delete
object Op extends WireEnum[Op]("op"):
  def all = values

enum PayloadType:
  case MatchMeta, Lineup, Event, TrackingFrame
object PayloadType extends WireEnum[PayloadType]("payload_type"):
  def all = values

enum EventType:
  case Pass, Shot, Carry, Dribble, Tackle, Interception, Clearance, Block, Pressure, BallReceipt,
    PossessionChange, Foul, SetPiece, GoalkeeperAction, Offside, Substitution, PeriodStart,
    PeriodEnd, VarReview
object EventType extends WireEnum[EventType]("event.type"):
  def all = values

enum Outcome:
  case Complete, Incomplete, Out, Offside, Intercepted, Goal, Saved, Blocked, OffTarget, Post, Won,
    Lost
object Outcome extends WireEnum[Outcome]("outcome"):
  def all = values

enum BodyPart:
  case RightFoot, LeftFoot, Head, Other
object BodyPart extends WireEnum[BodyPart]("body_part"):
  def all = values

enum PassHeight:
  case Ground, Low, High
object PassHeight extends WireEnum[PassHeight]("height"):
  def all = values

enum PassType:
  case OpenPlay, Corner, FreeKick, ThrowIn, GoalKick, KickOff
object PassType extends WireEnum[PassType]("pass_type"):
  def all = values

enum ShotSituation:
  case OpenPlay, SetPiece, Penalty, Counter, FreeKick
object ShotSituation extends WireEnum[ShotSituation]("situation"):
  def all = values

enum PossessionCause:
  case Tackle, Interception, OutOfPlay, Foul, Shot, Goal, Clearance, Other
object PossessionCause extends WireEnum[PossessionCause]("cause"):
  def all = values

enum Card:
  case None, Yellow, Red
object Card extends WireEnum[Card]("card"):
  def all = values

enum SetPieceKind:
  case Corner, FreeKick, ThrowIn, GoalKick, Penalty, KickOff
object SetPieceKind extends WireEnum[SetPieceKind]("set_piece.kind"):
  def all = values

enum GkAction:
  case Save, Claim, Punch, Sweep, Distribution
object GkAction extends WireEnum[GkAction]("gk.action"):
  def all = values

enum VarDecision:
  case GoalConfirmed, GoalDisallowed, PenaltyAwarded, RedCardUpgrade, NoChange
object VarDecision extends WireEnum[VarDecision]("var.decision"):
  def all = values

enum Provenance:
  case Computed, Provider, TrackingDerived, EventEstimate
object Provenance extends WireEnum[Provenance]("provenance"):
  def all = values

enum MatchStatus:
  case Scheduled, Live, HalfTime, FullTime, Abandoned
object MatchStatus extends WireEnum[MatchStatus]("status"):
  def all = values

enum Direction:
  case LeftToRight, RightToLeft
object Direction extends WireEnum[Direction]("attacking_direction"):
  def all = values

enum DataOrigin:
  case Synthetic, LicensedProvider
object DataOrigin extends WireEnum[DataOrigin]("data_origin"):
  def all = values

enum Position:
  case GK, RB, CB, LB, RWB, LWB, CDM, CM, CAM, RM, LM, RW, LW, ST, CF
object Position extends WireEnum[Position]("position"):
  def all = values
  override protected def toWire(e: Position): String = e.toString

enum BallState:
  case InPlay, Dead
object BallState extends WireEnum[BallState]("ball.state"):
  def all = values

enum TrackedRole:
  case Player, Goalkeeper
object TrackedRole extends WireEnum[TrackedRole]("role"):
  def all = values

enum OfficialRole:
  case Referee, AssistantReferee, FourthOfficial
object OfficialRole extends WireEnum[OfficialRole]("official.role"):
  def all = values

enum InsightKind:
  case Goal, RedCard, KeyChance, MomentumSwing, PressureShift, ControlVsChaos, RhythmChange,
    Milestone, PlayerSpotlight, TacticalShift, FullTime
object InsightKind extends WireEnum[InsightKind]("kind"):
  def all = values

enum Basis:
  case EventData, Tracking, EventEstimate
object Basis extends WireEnum[Basis]("basis"):
  def all = values

enum InsightStatus:
  case Active, Retracted
object InsightStatus extends WireEnum[InsightStatus]("status"):
  def all = values

enum OverlayOp:
  case Show, Retract
object OverlayOp extends WireEnum[OverlayOp]("overlay.op"):
  def all = values

enum OverlayType:
  case PlayerTag, SpeedCallout, StatCard, MomentumBar, InsightCard, MilestoneBadge,
    ScorelineUpdate, RecapCard, ShapeMap
object OverlayType extends WireEnum[OverlayType]("overlay.type"):
  def all = values

enum OverlayStyle:
  case LowerThird, CornerCard, FullWidthBanner, PitchOverlay
object OverlayStyle extends WireEnum[OverlayStyle]("style"):
  def all = values

enum Lang:
  case En, Hi, Es
object Lang extends WireEnum[Lang]("lang"):
  def all = values

enum Persona:
  case Analyst, Casual, ClubFan, PlayerFocus, MetricFocus
object Persona extends WireEnum[Persona]("persona"):
  def all = values
