package smi.api

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.yaml.v12.parser
import io.circe.{Json, JsonObject}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.FiniteDuration
import scala.io.Source

/** How a setting's value is checked. Values from App Configuration arrive as text. */
enum SettingKind:
  case Num(min: Double, max: Double)
  case Whole(min: Long, max: Long)
  case Flag
  case Choice(options: Set[String])
  case Text(pattern: String)

  def parse(raw: String): Either[String, Json] =
    val s = raw.trim
    this match
      case Num(lo, hi) =>
        s.toDoubleOption
          .filter(v => v >= lo && v <= hi)
          .map(Json.fromDoubleOrNull)
          .toRight(s"'$s' is not a number from $lo to $hi")
      case Whole(lo, hi) =>
        s.toLongOption
          .filter(v => v >= lo && v <= hi)
          .map(Json.fromLong)
          .toRight(s"'$s' is not a whole number from $lo to $hi")
      case Flag =>
        s.toLowerCase match
          case "true"  => Right(Json.True)
          case "false" => Right(Json.False)
          case _       => Left(s"'$s' is not true or false")
      case Choice(options) =>
        Either.cond(
          options(s),
          Json.fromString(s),
          s"'$s' is not one of ${options.toList.sorted.mkString(", ")}"
        )
      case Text(pattern) =>
        Either.cond(s.matches(pattern), Json.fromString(s), s"'$s' is not a valid value")

/** The settings in effect (ADR-019): the defaults in config/app.yaml (packaged as /app.yaml),
  * overridden by values from App Configuration. A value that does not parse, is out of range or
  * breaks a rule between settings is ignored (the default stays) and reported in `problems`.
  */
final case class Settings(
    values: Map[String, Json],
    sources: Map[String, String],
    problems: List[String]
):
  def num(key: String): Double = values(key).asNumber.map(_.toDouble).get
  def flag(key: String): Boolean = values(key).asBoolean.get
  def text(key: String): String = values(key).asString.get
  def fromStore(key: String): Boolean = sources.get(key).contains(Settings.Store)

  def maxDelayS: Double = num("match:max_delay_s")
  def signInRequired: Boolean = flag("access:sign_in_required")
  def corsOrigins: List[String] =
    text("access:cors_origins").split(",").map(_.trim).filter(_.nonEmpty).toList

  def asJson: Json = Json.obj(
    "settings" -> JsonObject.fromIterable(Settings.Spec.map((k, _) => k -> values(k))).asJson,
    "sources" -> JsonObject
      .fromIterable(Settings.Spec.map((k, _) => k -> sources(k).asJson))
      .asJson,
    "problems" -> problems.asJson
  )

object Settings:
  val Default = "default"
  val Store = "app_configuration"

  import SettingKind.*
  val Spec: List[(String, SettingKind)] = List(
    "match:seed" -> Whole(0, Int.MaxValue),
    "match:speed" -> Num(0.25, 600),
    "match:tracking" -> Flag,
    "match:narration_budget_s" -> Num(4, 60),
    "match:min_delay_s" -> Num(0, 600),
    "match:max_delay_s" -> Num(30, 600),
    "moments:model_min_importance" -> Num(0, 1),
    "moments:template_min_importance" -> Num(0, 1),
    "moments:card_min_show_ms" -> Whole(1000, 15000),
    "moments:model_slots" -> Whole(1, 4),
    "narration:model_deployment" -> Text("^[A-Za-z0-9._-]{1,64}$"),
    "narration:reasoning_effort" -> Choice(Set("minimal", "low", "medium")),
    "narration:max_attempts" -> Whole(1, 5),
    "narration:default_persona" -> Choice(
      Set("casual", "analyst", "club_fan", "player_focus", "metric_focus")
    ),
    "narration:default_lang" -> Choice(Set("en", "hi", "es")),
    "access:sign_in_required" -> Flag,
    "access:cors_origins" -> Text("""^(\*|https?://[^,\s]+(\s*,\s*https?://[^,\s]+)*)$""")
  )
  private val kinds = Spec.toMap

  /** config/app.yaml flattened to group:name keys, values as text (as App Configuration has them).
    */
  lazy val defaultText: Map[String, String] =
    val yaml =
      Source.fromInputStream(getClass.getResourceAsStream("/app.yaml"), UTF_8.name).mkString
    def flatten(prefix: String, j: Json): List[(String, String)] = j.asObject match
      case Some(o) =>
        o.toList.flatMap((k, v) => flatten(if prefix.isEmpty then k else s"$prefix:$k", v))
      case None => List(prefix -> j.asString.getOrElse(j.noSpaces))
    parser
      .parse(yaml)
      .fold(e => throw IllegalStateException(s"app.yaml: ${e.getMessage}"), flatten("", _))
      .toMap

  /** The settings with `overrides` (App Configuration values) applied over the defaults. */
  def resolve(overrides: Map[String, String]): Settings =
    val defaults = Spec.map { (k, kind) =>
      k -> kind
        .parse(defaultText.getOrElse(k, sys.error(s"app.yaml has no $k")))
        .fold(e => sys.error(s"app.yaml $k: $e"), identity)
    }.toMap
    val parsed = overrides.toList.sortBy(_._1).map { (k, raw) =>
      kinds
        .get(k)
        .toRight(s"$k: not a setting")
        .flatMap(_.parse(raw).left.map(e => s"$k: $e"))
        .map(k -> _)
    }
    val applied = parsed.collect { case Right(kv) => kv }.toMap
    val unknownOrBad = parsed.collect { case Left(e) => e }
    // Rules between settings: a store value that breaks one is dropped, and the pair falls back.
    def pair(lo: String, hi: String, merged: Map[String, Json]): Option[String] =
      val (l, h) = (merged(lo).asNumber.get.toDouble, merged(hi).asNumber.get.toDouble)
      Option.when(l > h)(s"$lo ($l) is above $hi ($h)")
    val first = defaults ++ applied
    val rules = List(
      ("moments:template_min_importance", "moments:model_min_importance"),
      ("match:min_delay_s", "match:max_delay_s")
    )
    val broken = rules.flatMap((lo, hi) => pair(lo, hi, first).map(e => (List(lo, hi), e)))
    val dropped = broken.flatMap(_._1).toSet
    val values = defaults ++ applied.filterNot((k, _) => dropped(k))
    Settings(
      values,
      Spec
        .map((k, _) => k -> (if applied.contains(k) && !dropped(k) then Store else Default))
        .toMap,
      unknownOrBad ++ broken.map(_._2)
    )

  lazy val default: Settings = resolve(Map.empty)

/** Where settings are kept besides the defaults: App Configuration in Azure, none locally. */
trait SettingsSource:
  def name: String
  def read: IO[Map[String, String]]

/** The settings in effect, refreshed from the source (ADR-019). */
final class SettingsHub(ref: Ref[IO, Settings], source: Option[SettingsSource], log: Log):
  def get: IO[Settings] = ref.get

  /** Reads the source now; on failure keeps the settings it had. */
  def reload: IO[Settings] = source match
    case None => ref.get
    case Some(s) =>
      s.read.attempt.flatMap {
        case Right(overrides) =>
          val next = Settings.resolve(overrides)
          ref.getAndSet(next).flatMap { before =>
            val changed = next.values.toList.filter((k, v) => !before.values.get(k).contains(v))
            val notes = changed.map((k, v) => s"setting $k = ${v.noSpaces}") ++
              next.problems.filterNot(before.problems.contains).map(p => s"setting ignored: $p")
            notes.traverse_(log.info).as(next)
          }
        case Left(e) => log.error(s"reading settings from ${s.name} failed", e) >> ref.get
      }

  def refreshEvery(every: FiniteDuration): IO[Nothing] = (reload >> IO.sleep(every)).foreverM

object SettingsHub:
  def apply(source: Option[SettingsSource], log: Log): IO[SettingsHub] =
    Ref.of[IO, Settings](Settings.default).map(new SettingsHub(_, source, log))

  /** The defaults only (tests, local runs without a store). */
  def static: SettingsHub =
    new SettingsHub(Ref.unsafe[IO, Settings](Settings.default), None, Log.silent)
