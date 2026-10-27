package smi

import com.networknt.schema.{
  ExecutionContext,
  InputFormat,
  Schema,
  SchemaRegistry,
  SpecificationVersion
}
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

/** Test helper: loads the contract-pack schemas, examples and negative cases from /schema. */
object SchemaFiles:
  val dir: Path = Paths.get("../schema")
  val examplesDir: Path = dir.resolve("examples")

  /** Which schema each example file conforms to. A new example file must be added here. */
  val schemaForExample: Map[String, String] = Map(
    "examples.ndjson" -> "canonical.schema.json",
    "insight.example.json" -> "insight.schema.json",
    "overlay.example.json" -> "overlay.schema.json",
    "overlay-shape.example.json" -> "overlay.schema.json",
    "insight-full-time.example.json" -> "insight.schema.json",
    "overlay-recap.example.json" -> "overlay.schema.json"
  )

  def readString(rel: String): String = Files.readString(dir.resolve(rel))

  def exampleFiles: List[String] =
    Files.list(examplesDir).iterator.asScala.map(_.getFileName.toString).toList.sorted

  /** Documents in an example file: one per line for NDJSON, else the whole file. */
  def exampleDocs(file: String): List[String] =
    val text = Files.readString(examplesDir.resolve(file))
    if file.endsWith(".ndjson") then text.linesIterator.filter(_.trim.nonEmpty).toList
    else List(text)

  def json(s: String): Json = parse(s).fold(throw _, identity)

  private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

  def schema(name: String): Schema = registry.getSchema(readString(name))

  /** Validation error messages; empty when the document conforms. */
  def errors(schema: Schema, json: String): List[String] =
    val enableFormats: Consumer[ExecutionContext] =
      ctx => ctx.executionConfig(c => c.formatAssertionsEnabled(true))
    schema.validate(json, InputFormat.JSON, enableFormats).asScala.map(_.getMessage).toList

  /** A known-bad variant of an example, from schema/negatives.json. */
  final case class NegativeCase(name: String, file: String, doc: String)

  lazy val negativeCases: List[NegativeCase] =
    json(readString("negatives.json")).hcursor.downField("cases").values.get.toList.map { c =>
      val cur = c.hcursor
      val name = cur.get[String]("name").toOption.get
      val file = cur.get[String]("file").toOption.get
      val wanted = cur.downField("match").focus.flatMap(_.asObject).map(_.toList).getOrElse(Nil)
      val base = exampleDocs(file).map(json).find { d =>
        wanted.forall((k, v) => d.hcursor.downField(k).focus.contains(v))
      }
      val path = cur.downField("path").focus.flatMap(_.asArray).get.toList
      val mutated = base.flatMap(b => mutate(b, path, cur.get[String]("action").toOption.get, c))
      NegativeCase(
        name,
        file,
        mutated.map(_.noSpaces).getOrElse(sys.error(s"cannot apply '$name'"))
      )
    }

  private def mutate(doc: Json, path: List[Json], action: String, spec: Json): Option[Json] =
    val target = path.foldLeft(doc.hcursor: ACursor) { (cur, step) =>
      step.asString.map(cur.downField).orElse(step.asNumber.flatMap(_.toInt).map(cur.downN)).get
    }
    action match
      case "set" =>
        val value = spec.hcursor.downField("value").focus.get
        if target.succeeded then target.set(value).top
        else path.last.asString.flatMap(k => setNew(doc, path.init, k, value))
      case "drop" => target.delete.top
      case other  => sys.error(s"unknown action $other")

  /** Adds a field that does not exist yet (cursors can only replace existing values). */
  private def setNew(doc: Json, parents: List[Json], key: String, value: Json): Option[Json] =
    val parent = parents.foldLeft(doc.hcursor: ACursor) { (cur, step) =>
      step.asString.map(cur.downField).orElse(step.asNumber.flatMap(_.toInt).map(cur.downN)).get
    }
    parent.withFocus(_.mapObject(_.add(key, value))).top
