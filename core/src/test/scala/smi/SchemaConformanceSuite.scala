package smi

import munit.FunSuite

/** Scala twin of schema/validate_examples.py: examples conform, schema/negatives.json is rejected.
  */
class SchemaConformanceSuite extends FunSuite:
  private val schemas = SchemaFiles.schemaForExample.view.mapValues(SchemaFiles.schema).toMap

  test("every example document conforms to its schema") {
    for
      (file, schema) <- schemas
      (doc, i) <- SchemaFiles.exampleDocs(file).zipWithIndex
    do assertEquals(SchemaFiles.errors(schema, doc), Nil, s"$file #${i + 1}")
  }

  test("the schema rejects every negative case") {
    SchemaFiles.negativeCases.foreach { c =>
      assert(SchemaFiles.errors(schemas(c.file), c.doc).nonEmpty, s"schema accepted '${c.name}'")
    }
  }
