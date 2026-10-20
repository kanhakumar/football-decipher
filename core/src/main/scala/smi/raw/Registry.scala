package smi.raw

import io.circe.{Codec, Decoder}
import io.circe.yaml.v12.parser
import java.nio.charset.StandardCharsets.UTF_8
import scala.io.Source
import smi.model.*

enum QualifierKind:
  case X, Y, Number, Int, String, Flag, Player, Team, Event, Events
object QualifierKind extends WireEnum[QualifierKind]("qualifier.kind"):
  def all = values

final case class QualifierSpec(name: String, kind: QualifierKind)
object QualifierSpec:
  given Codec.AsObject[QualifierSpec] = Wire.codec

/** The provider's ID tables, loaded from config (`raw/registry.yaml`). */
final case class Registry(
    provider: String,
    dataOrigin: DataOrigin,
    videoOffsetMs: Long,
    teams: Map[String, TeamId],
    matchStatus: Map[String, MatchStatus],
    eventTypes: Map[String, EventType],
    qualifiers: Map[String, QualifierSpec]
):
  private def ints[A](m: Map[String, A]): Map[Int, A] = m.map((k, v) => k.toInt -> v)

  lazy val teamById: Map[Int, TeamId] = ints(teams)
  lazy val teamIdOf: Map[TeamId, Int] = teamById.map(_.swap)
  lazy val statusById: Map[Int, MatchStatus] = ints(matchStatus)
  lazy val statusIdOf: Map[MatchStatus, Int] = statusById.map(_.swap)
  lazy val typeById: Map[Int, EventType] = ints(eventTypes)
  lazy val typeIdOf: Map[EventType, Int] = typeById.map(_.swap)
  lazy val qualifierById: Map[Int, QualifierSpec] = ints(qualifiers)
  lazy val qualifierIdOf: Map[String, Int] = qualifierById.map((id, q) => q.name -> id)

  def kindOf(name: String): Option[QualifierKind] =
    qualifierIdOf.get(name).flatMap(qualifierById.get).map(_.kind)

object Registry:
  given Codec.AsObject[Registry] = Wire.codec

  def parse(yaml: String): Either[String, Registry] =
    parser.parse(yaml).flatMap(Decoder[Registry].decodeJson).left.map(_.getMessage)

  lazy val default: Registry =
    val text = Source
      .fromInputStream(getClass.getResourceAsStream("/raw/registry.yaml"), UTF_8.name)
      .mkString
    parse(text).fold(e => throw IllegalStateException(s"raw registry: $e"), identity)
