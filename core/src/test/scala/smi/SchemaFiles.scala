package smi

import com.networknt.schema.{
  ExecutionContext,
  InputFormat,
  Schema,
  SchemaRegistry,
  SpecificationVersion
}
import java.nio.file.{Files, Path, Paths}
import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

/** Test helper: loads the contract-pack schemas and examples from /schema. */
object SchemaFiles:
  val dir: Path = Paths.get("../schema")

  def readString(rel: String): String = Files.readString(dir.resolve(rel))

  def ndjson(rel: String): List[String] =
    readString(rel).linesIterator.filter(_.trim.nonEmpty).toList

  private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

  def schema(name: String): Schema = registry.getSchema(readString(name))

  /** Validation error messages; empty when the document conforms. */
  def errors(schema: Schema, json: String): List[String] =
    val enableFormats: Consumer[ExecutionContext] =
      ctx => ctx.executionConfig(c => c.formatAssertionsEnabled(true))
    schema.validate(json, InputFormat.JSON, enableFormats).asScala.map(_.getMessage).toList
