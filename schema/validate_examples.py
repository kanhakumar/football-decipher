"""Validate the contract examples against the JSON Schemas.

Checks every schema is a valid draft 2020-12 schema, every example validates,
and a set of known-bad mutations of the examples is rejected.

Usage: python schema/validate_examples.py
Exit code 0 when everything passes, 1 otherwise.
"""

from __future__ import annotations

import copy
import json
import sys
from collections.abc import Callable
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

SCHEMA_DIR = Path(__file__).resolve().parent
EXAMPLES_DIR = SCHEMA_DIR / "examples"

Doc = dict
Mutation = tuple[str, Callable[[Doc], None]]


def load_validator(name: str) -> Draft202012Validator:
    schema = json.loads((SCHEMA_DIR / name).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def errors_of(validator: Draft202012Validator, doc: Doc) -> list[str]:
    return [
        f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}"
        for e in validator.iter_errors(doc)
    ]


def first_line_of_type(lines: list[Doc], payload_type: str, op: str = "create") -> Doc:
    return next(d for d in lines if d["payload_type"] == payload_type and d["op"] == op)


def set_key(path: list, value) -> Callable[[Doc], None]:
    def apply(doc: Doc) -> None:
        target = doc
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value

    return apply


def drop_key(path: list) -> Callable[[Doc], None]:
    def apply(doc: Doc) -> None:
        target = doc
        for key in path[:-1]:
            target = target[key]
        del target[path[-1]]

    return apply


ENVELOPE_NEGATIVES: list[tuple[str, Mutation]] = [
    ("event", ("wrong schema_version", set_key(["schema_version"], "2.0"))),
    ("event", ("negative seq", set_key(["seq"], -1))),
    ("event", ("unknown source", set_key(["source"], "vendor"))),
    ("event", ("missing payload", drop_key(["payload"]))),
    ("event", ("extra envelope field", set_key(["extra"], 1))),
    ("event", ("bad emitted_at", set_key(["emitted_at"], "yesterday"))),
    ("event", ("bad event_id", set_key(["payload", "event_id"], "417"))),
    ("event", ("x beyond pitch", set_key(["payload", "x"], 105.5))),
    ("event", ("y below pitch", set_key(["payload", "y"], -0.1))),
    ("event", ("unknown event type", set_key(["payload", "type"], "teleport"))),
    ("lineup", ("bad team_id", set_key(["payload", "team_id"], "home"))),
]

INSIGHT_NEGATIVES: list[Mutation] = [
    ("bad insight_id", set_key(["insight_id"], "insight-42")),
    ("unknown kind", set_key(["kind"], "vibes")),
    ("importance above 1", set_key(["importance"], 1.5)),
    ("empty facts", set_key(["facts"], {})),
    ("no evidence", set_key(["evidence_event_ids"], [])),
    ("duplicate evidence", set_key(["evidence_event_ids"], ["E_1", "E_1"])),
    ("prose field", set_key(["text"], "A great moment.")),
    ("capability level 3", set_key(["capability_level"], 3)),
]

OVERLAY_NEGATIVES: list[Mutation] = [
    ("unknown op", set_key(["op"], "hide")),
    ("missing t_start_ms", drop_key(["t_start_ms"])),
    ("negative duration", set_key(["duration_ms"], -1)),
    ("bad basis", set_key(["basis"], "guess")),
]


class Report:
    def __init__(self) -> None:
        self.failures = 0
        self.checks = 0

    def expect_valid(self, label: str, validator: Draft202012Validator, doc: Doc) -> None:
        self.checks += 1
        errs = errors_of(validator, doc)
        if errs:
            self.failures += 1
            print(f"FAIL  {label} should be valid:")
            for err in errs:
                print(f"        {err}")

    def expect_invalid(self, label: str, validator: Draft202012Validator, doc: Doc) -> None:
        self.checks += 1
        if not errors_of(validator, doc):
            self.failures += 1
            print(f"FAIL  {label} should be rejected but validated")


def run_negatives(report: Report, prefix: str, validator, base: Doc, mutations) -> None:
    for name, mutate in mutations:
        doc = copy.deepcopy(base)
        try:
            mutate(doc)
        except (KeyError, TypeError) as exc:
            report.checks += 1
            report.failures += 1
            print(f"FAIL  {prefix} negative '{name}' could not be applied: {exc!r}")
            continue
        report.expect_invalid(f"{prefix} negative '{name}'", validator, doc)


def main() -> int:
    canonical = load_validator("canonical.schema.json")
    insight = load_validator("insight.schema.json")
    overlay = load_validator("overlay.schema.json")
    report = Report()

    ndjson_path = EXAMPLES_DIR / "examples.ndjson"
    lines = []
    for n, raw in enumerate(ndjson_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw.strip():
            continue
        doc = json.loads(raw)
        lines.append(doc)
        report.expect_valid(f"{ndjson_path.name}:{n} ({doc.get('payload_type')})", canonical, doc)

    insight_doc = json.loads((EXAMPLES_DIR / "insight.example.json").read_text(encoding="utf-8"))
    overlay_doc = json.loads((EXAMPLES_DIR / "overlay.example.json").read_text(encoding="utf-8"))
    report.expect_valid("insight.example.json", insight, insight_doc)
    report.expect_valid("overlay.example.json", overlay, overlay_doc)

    for payload_type, (name, mutate) in ENVELOPE_NEGATIVES:
        base = first_line_of_type(lines, payload_type)
        run_negatives(report, f"envelope/{payload_type}", canonical, base, [(name, mutate)])
    run_negatives(report, "insight", insight, insight_doc, INSIGHT_NEGATIVES)
    run_negatives(report, "overlay", overlay, overlay_doc, OVERLAY_NEGATIVES)

    passed = report.checks - report.failures
    print(f"{passed}/{report.checks} checks passed")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
