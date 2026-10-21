"""Generate the Pydantic models in smi_agents/models from the JSON Schemas in /schema.

Usage: python scripts/gen_models.py [out_dir]   (default: src/smi_agents/models)
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = {"insight": "Insight", "overlay": "Overlay"}


def generate(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, cls in SCHEMAS.items():
        subprocess.run(
            [
                sys.executable,
                "-m",
                "datamodel_code_generator",
                "--input",
                str(ROOT / "schema" / f"{name}.schema.json"),
                "--input-file-type",
                "jsonschema",
                "--output",
                str(out_dir / f"{name}.py"),
                "--class-name",
                cls,
                "--output-model-type",
                "pydantic_v2.BaseModel",
                "--target-python-version",
                "3.11",
                "--use-annotated",
                "--field-constraints",
                "--use-double-quotes",
                "--disable-timestamp",
                "--ignore-pyproject",
                "--formatters",
                "builtin",
            ],
            check=True,
        )
    # Format with the project's ruff settings wherever the output goes (tests use a temp dir).
    config = str(ROOT / "agents/pyproject.toml")
    files = [str(out_dir / f"{name}.py") for name in SCHEMAS]
    ruff = [sys.executable, "-m", "ruff"]
    subprocess.run(
        [*ruff, "check", "--fix", "--select", "I", "--config", config, *files], check=True
    )
    subprocess.run([*ruff, "format", "--config", config, *files], check=True)
    (out_dir / "__init__.py").write_text(
        '"""Generated from /schema by scripts/gen_models.py. Do not edit."""\n', encoding="utf-8"
    )


if __name__ == "__main__":
    generate(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "agents/src/smi_agents/models")
