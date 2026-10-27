import importlib.util
import json
import tempfile
from pathlib import Path

from conftest import SCHEMA
from smi_agents.models.insight import Insight
from smi_agents.models.overlay import Overlay

AGENTS = Path(__file__).resolve().parents[1]


def test_generated_models_are_up_to_date():
    spec = importlib.util.spec_from_file_location("gen_models", AGENTS / "scripts/gen_models.py")
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    # Inside agents/, so the generator and ruff see the same project settings as the real run.
    with tempfile.TemporaryDirectory(dir=AGENTS, prefix=".gen-check-") as tmp:
        gen.generate(Path(tmp))
        for name in ("insight", "overlay"):
            committed = (AGENTS / "src/smi_agents/models" / f"{name}.py").read_text("utf-8")
            assert (Path(tmp) / f"{name}.py").read_text("utf-8") == committed, (
                f"{name}.py is stale: run scripts/gen_models.py"
            )


def test_schema_examples_parse():
    for name in ("insight", "insight-full-time"):
        Insight.model_validate(
            json.loads((SCHEMA / f"examples/{name}.example.json").read_text("utf-8"))
        )
    for name in ("overlay", "overlay-recap"):
        Overlay.model_validate(
            json.loads((SCHEMA / f"examples/{name}.example.json").read_text("utf-8"))
        )
