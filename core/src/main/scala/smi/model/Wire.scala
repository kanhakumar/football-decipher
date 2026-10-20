package smi.model

import cats.syntax.all.*
import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import scala.collection.immutable.ListMap
import scala.deriving.Mirror

/** JSON wire conventions shared by every model: snake_case names, unknown fields rejected (schemas
  * use additionalProperties: false), absent optionals omitted rather than null.
  */
object Wire:
  given config: Configuration = Configuration.default.withSnakeCaseMemberNames.withStrictDecoding

  def snake(name: String): String =
    name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase

  inline def codec[A](using inline m: Mirror.Of[A]): Codec.AsObject[A] =
    val derived = ConfiguredCodec.derived[A](using config)
    Codec.AsObject.from(
      derived,
      Encoder.AsObject.instance(a => derived.encodeObject(a).filter((_, v) => !v.isNull))
    )

  /** Adds schema constraints that the type alone cannot express. */
  def checked[A](c: Codec.AsObject[A])(errors: A => List[String]): Codec.AsObject[A] =
    Codec.AsObject.from(
      c.emap(a => if errors(a).isEmpty then Right(a) else Left(errors(a).mkString("; "))),
      c
    )

  given listMapEncoder[A: Encoder]: Encoder[ListMap[String, A]] =
    Encoder.instance(m => Json.fromFields(m.toList.map((k, v) => k -> v.asJson)))

  given listMapDecoder[A: Decoder]: Decoder[ListMap[String, A]] =
    Decoder.decodeJsonObject.emap { obj =>
      obj.toList
        .traverse((k, v) => v.as[A].map(k -> _).leftMap(e => s"$k: ${e.message}"))
        .map(ListMap.from)
    }

/** Constraint checks returning error messages; an empty list means valid. */
object Check:
  def range(name: String, v: Double, lo: Double, hi: Double): List[String] =
    if v >= lo && v <= hi then Nil else List(s"$name=$v outside [$lo, $hi]")

  def atLeast(name: String, v: Double, lo: Double): List[String] =
    if v >= lo then Nil else List(s"$name=$v below $lo")

  def above(name: String, v: Double, lo: Double): List[String] =
    if v > lo then Nil else List(s"$name=$v must be > $lo")

  def size(name: String, n: Int, lo: Int, hi: Int): List[String] =
    if n >= lo && n <= hi then Nil else List(s"$name has $n items, expected $lo..$hi")

  def that(ok: Boolean, msg: => String): List[String] = if ok then Nil else List(msg)

  def opt[A](o: Option[A])(f: A => List[String]): List[String] = o.toList.flatMap(f)

/** Base for companions of enums whose JSON form is a fixed string. */
abstract class WireEnum[E](label: String):
  def all: Array[E]
  protected def toWire(e: E): String = Wire.snake(e.toString)

  private lazy val byWire: Map[String, E] = all.map(e => toWire(e) -> e).toMap

  extension (e: E) def wire: String = toWire(e)

  def fromWire(s: String): Either[String, E] =
    byWire.get(s).toRight(s"$label: unknown value '$s'")

  given Codec[E] = Codec.from(
    Decoder.decodeString.emap(fromWire),
    Encoder.encodeString.contramap(toWire)
  )
