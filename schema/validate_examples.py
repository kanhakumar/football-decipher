"""Validate the contract examples against the JSON Schemas.

Checks every schema is a valid draft 2020-12 schema, every example validates,
and every known-bad variant in negatives.json is rejected. The Scala golden
tests read the same negatives.json, so both sides reject the same cases.

Usage: python schema/validate_examples.py
Exit code 0 when everything passes, 1 otherwise.
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

SCHEMA_DIR = Path(__file__).resolve().parent
EXAMPLES_DIR = SCHEMA_DIR / "examples"

SCHEMA_FOR_FILE = {
    "examples.ndjson": "canonical.schema.json",
    "insight.example.json": "insight.schema.json",
    "overlay.example.json": "overlay.schema.json",
    "overlay-shape.example.json": "overlay.schema.json",
    "insight-full-time.example.json": "insight.schema.json",
    "overlay-recap.example.json": "overlay.schema.json",
}

Doc = dict


def load_validator(name: str) -> Draft202012Validator:
    schema = json.loads((SCHEMA_DIR / name).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def errors_of(validator: Draft202012Validator, doc: Doc) -> list[str]:
    return [
        f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}"
        for e in validator.iter_errors(doc)
    ]


def load_examples() -> dict[str, list[Doc]]:
    """Every example file as a list of documents (one per NDJSON line)."""
    docs: dict[str, list[Doc]] = {}
    for name in SCHEMA_FOR_FILE:
        text = (EXAMPLES_DIR / name).read_text(encoding="utf-8")
        if name.endswith(".ndjson"):
            docs[name] = [
                json.loads(line) for line in text.splitlines() if line.strip()
            ]
        else:
            docs[name] = [json.loads(text)]
    return docs


def base_doc(case: dict, examples: dict[str, list[Doc]]) -> Doc:
    wanted = case.get("match", {})
    return next(
        d
        for d in examples[case["file"]]
        if all(d.get(k) == v for k, v in wanted.items())
    )


def apply_case(case: dict, doc: Doc) -> Doc:
    doc = copy.deepcopy(doc)
    *parents, last = case["path"]
    target = doc
    for key in parents:
        target = target[key]
    if case["action"] == "set":
        target[last] = case["value"]
    else:
        del target[last]
    return doc


class Report:
    def __init__(self) -> None:
        self.failures = 0
        self.checks = 0

    def check(self, ok: bool, message: str, details: list[str] | None = None) -> None:
        self.checks += 1
        if not ok:
            self.failures += 1
            print(f"FAIL  {message}")
            for line in details or []:
                print(f"        {line}")


def main() -> int:
    validators = {f: load_validator(s) for f, s in SCHEMA_FOR_FILE.items()}
    examples = load_examples()
    report = Report()

    for name, docs in examples.items():
        for n, doc in enumerate(docs, start=1):
            errs = errors_of(validators[name], doc)
            report.check(not errs, f"{name}:{n} should be valid", errs)

    cases = json.loads((SCHEMA_DIR / "negatives.json").read_text(encoding="utf-8"))[
        "cases"
    ]
    for case in cases:
        label = f"negative '{case['name']}' ({case['file']})"
        try:
            doc = apply_case(case, base_doc(case, examples))
        except (KeyError, IndexError, StopIteration) as exc:
            report.check(False, f"{label} could not be applied: {exc!r}")
            continue
        report.check(
            bool(errors_of(validators[case["file"]], doc)),
            f"{label} should be rejected",
        )

    print(f"{report.checks - report.failures}/{report.checks} checks passed")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
