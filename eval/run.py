"""The eval gate: replay the demo match through the narration workflow and fail on regression.

  python eval/run.py --offline                 # stub model + recorded core answers (CI, every push)
  python eval/run.py --online [--core URL]     # running core + Foundry model (after deploy)
  ... --update-baseline                        # accept the current scores as the new baseline

For every insight at or above the importance threshold, in every persona and language, it narrates
the moment and checks: every number matches a tool answer (independent of the Verifier), lines are
verified, how often the template fallback was needed, first-attempt pass rate and latency. Online it
also scores English lines with Foundry evaluators (groundedness, relevance, fluency, coherence).
Run with the agents venv: agents/.venv/Scripts/python eval/run.py --offline
"""

import argparse
import asyncio
import json
import logging
import os
import statistics
import sys
import time
from pathlib import Path
from typing import Any

import httpx
import yaml
from mcp import Client

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

from number_match import check  # noqa: E402

from smi_agents import overlay  # noqa: E402
from smi_agents.facts import McpTools  # noqa: E402
from smi_agents.mcp_server import build_server  # noqa: E402
from smi_agents.model import Model, StubModel  # noqa: E402
from smi_agents.types import LANGS, NarrationRequest  # noqa: E402
from smi_agents.workflow import Narrator  # noqa: E402

RECORDED = ROOT / "agents" / "tests" / "fixtures" / "core-154.json"
PERSONAS = ["casual", "analyst", "club_fan", "player_focus", "metric_focus"]


class RecordingTools:
    """MCP tools that remember what they answered, so lines can be checked against raw answers."""

    def __init__(self, tools: McpTools) -> None:
        self.tools = tools
        self.answers: list[tuple[str, dict[str, Any]]] = []

    async def call(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        answer = await self.tools.call(name, args)
        self.answers.append((name, answer))
        return answer


def usable(name: str, answer: dict[str, Any]) -> list[Any]:
    """The parts of a tool answer a line may quote: insight facts, evidence count and minutes,
    and metric values. Not shirt numbers, pitch coordinates or seconds."""
    if name == "get_insight":
        evidence = answer.get("evidence", {})
        minutes = [e.get("minute") for e in evidence.get("sample", [])]
        return [answer["insight"].get("facts", {}), evidence.get("count"), minutes]
    if name in ("team_stats", "player_stats", "player_workload"):
        return [m.get("value") for m in answer.get("metrics", [])]
    return []


def recorded_core() -> httpx.AsyncBaseTransport:
    responses = json.loads(RECORDED.read_text("utf-8"))["responses"]

    def reply(request: httpx.Request) -> httpx.Response:
        query = request.url.query.decode()
        key = f"{request.method} {request.url.path}" + (f"?{query}" if query else "")
        r = responses.get(key)
        if r is None:
            return httpx.Response(404, json={"error": f"not recorded: {key}"})
        return httpx.Response(r["status"], json=r["body"])

    return httpx.MockTransport(reply)


async def replay_on(http: httpx.AsyncClient, seed: int) -> None:
    """Online: play the demo match on the core at full speed and wait for full time."""
    res = await http.post("/api/match/start", json={"seed": seed, "speed": 600})
    res.raise_for_status()
    for _ in range(600):
        state = (await http.get("/api/match/state")).json()
        if state["status"] == "finished":
            return
        await asyncio.sleep(1)
    raise TimeoutError("the core did not finish the replay in 10 minutes")


def requests_for(insight: dict[str, Any], persona: str, lang: str) -> NarrationRequest:
    teams, players = insight["teams"], insight.get("players") or []
    extra: dict[str, Any] = {}
    if persona == "club_fan":
        extra["favourite_team"] = teams[0]
    elif persona == "player_focus" and players:
        extra["favourite_player"] = players[0]
    elif persona == "metric_focus":
        extra["metric_focus"] = "xg"
    return NarrationRequest(insight_id=insight["insight_id"], persona=persona, lang=lang, **extra)


def foundry_scores(lines: list[dict[str, Any]], sample: int) -> dict[str, float]:
    """Foundry evaluators on English lines, judged by the deployed model (online only)."""
    from azure.ai.evaluation import (
        AzureOpenAIModelConfiguration,
        CoherenceEvaluator,
        FluencyEvaluator,
        GroundednessEvaluator,
        RelevanceEvaluator,
    )
    from azure.identity import DefaultAzureCredential

    config = AzureOpenAIModelConfiguration(
        azure_endpoint=os.environ["AZURE_OPENAI_ENDPOINT"],
        azure_deployment=os.environ.get("SMI_MODEL_DEPLOYMENT", "gpt-5-mini"),
    )
    credential = DefaultAzureCredential()
    evaluators = {
        "groundedness": GroundednessEvaluator(config, credential=credential),
        "relevance": RelevanceEvaluator(config, credential=credential),
        "fluency": FluencyEvaluator(config, credential=credential),
        "coherence": CoherenceEvaluator(config, credential=credential),
    }
    english = [line for line in lines if line["lang"] == "en"][:sample]
    scores: dict[str, list[float]] = {name: [] for name in evaluators}
    for line in english:
        text = f"{line['headline']} {line['body']}"
        query = f"Explain this football moment for a {line['persona']} viewer."
        context = json.dumps(line["facts"])
        for name, evaluate in evaluators.items():
            args = {"response": text}
            if name in ("groundedness",):
                args["context"] = context
            if name in ("relevance", "coherence"):
                args["query"] = query
            result = evaluate(**args)
            scores[name].append(float(result[name]))
    return {f"foundry_{k}": round(statistics.mean(v), 2) for k, v in scores.items() if v}


async def run(args: argparse.Namespace, config: dict[str, Any]) -> dict[str, Any]:
    match = config["match"]
    if args.online:
        from smi_agents.model import FoundryModel

        model: Model = FoundryModel.from_env()
        http = httpx.AsyncClient(base_url=args.core, timeout=60)
        await replay_on(http, match["seed"])
    else:
        model = StubModel()
        http = httpx.AsyncClient(base_url="http://recorded.core", transport=recorded_core())

    lines: list[dict[str, Any]] = []
    latencies: list[float] = []
    async with http, Client(build_server(http)) as client:
        tools = RecordingTools(McpTools(client))
        listed = await tools.call("list_insights", {"min_importance": 0.0})
        moments = [i for i in listed["insights"] if i["importance"] >= match["min_importance"]]
        narrator = Narrator(tools, model)
        number = 0
        for insight in moments:
            for persona in PERSONAS:
                for lang in LANGS:
                    tools.answers.clear()
                    started = time.perf_counter()
                    n = await narrator.narrate(requests_for(insight, persona, lang))
                    latencies.append((time.perf_counter() - started) * 1000)
                    number += 1
                    shown = overlay.show(n, overlay.overlay_id(number))
                    answers = [part for name, a in tools.answers for part in usable(name, a)]
                    first = all(a.attempt == 1 and a.ok for a in n.trail)
                    lines.append(
                        {
                            "insight_id": insight["insight_id"],
                            "persona": persona,
                            "lang": lang,
                            "headline": shown["content"]["headline"],
                            "body": shown["content"].get("body", ""),
                            "unmatched": check(shown, answers),
                            "verified": n.verified,
                            "template_fallback": n.template_fallback,
                            "first_attempt": first,
                            "facts": insight["facts"],
                        }
                    )

    total = len(lines)
    scores: dict[str, Any] = {
        "lines": total,
        "insights": len({line["insight_id"] for line in lines}),
        "number_match_rate": round(sum(not line["unmatched"] for line in lines) / total, 4),
        "verified_rate": round(sum(line["verified"] for line in lines) / total, 4),
        "fallback_rate": round(sum(line["template_fallback"] for line in lines) / total, 4),
        "first_attempt_rate": round(sum(line["first_attempt"] for line in lines) / total, 4),
        "latency_p95_ms": round(statistics.quantiles(latencies, n=20)[-1], 1),
        "model": model.name,
    }
    if args.online:
        scores |= await asyncio.to_thread(foundry_scores, lines, config["foundry"]["sample"])
    return {"scores": scores, "lines": lines}


def judge(
    scores: dict[str, Any], config: dict[str, Any], baseline: dict[str, Any] | None
) -> list[str]:
    """Threshold misses and regressions against the baseline (empty = the gate passes)."""
    problems = []
    for metric, rule in config["thresholds"].items():
        if metric not in scores:
            continue
        value = scores[metric]
        if "min" in rule and value < rule["min"]:
            problems.append(f"{metric} {value} is below the minimum {rule['min']}")
        if "max" in rule and value > rule["max"]:
            problems.append(f"{metric} {value} is above the maximum {rule['max']}")
    if baseline:
        tolerance = config["regression_tolerance"]
        for metric, direction in config["higher_is_better"].items():
            if metric in scores and metric in baseline:
                drop = (baseline[metric] - scores[metric]) * (1 if direction else -1)
                if drop > tolerance * max(abs(baseline[metric]), 1e-9):
                    problems.append(
                        f"{metric} regressed: {scores[metric]} against baseline {baseline[metric]}"
                    )
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--offline", action="store_true")
    mode.add_argument("--online", action="store_true")
    parser.add_argument("--core", default=os.environ.get("SMI_CORE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--config", type=Path, default=HERE / "config.yaml")
    parser.add_argument("--report", type=Path, default=HERE / "out" / "report.json")
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()
    logging.getLogger("agent_framework").setLevel(logging.WARNING)  # no per-superstep lines

    config = yaml.safe_load(args.config.read_text("utf-8"))
    name = "online" if args.online else "offline"
    baseline_file = HERE / "baseline.json"
    baselines = json.loads(baseline_file.read_text("utf-8")) if baseline_file.exists() else {}

    result = asyncio.run(run(args, config))
    scores = result["scores"]
    problems = judge(scores, config, baselines.get(name))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report = {"mode": name, "scores": scores, "problems": problems, "lines": result["lines"]}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")

    print(json.dumps({"mode": name, **scores}, indent=1))
    for line in result["lines"]:
        if line["unmatched"]:
            print(
                f"unmatched {line['unmatched']} in {line['insight_id']} {line['persona']} "
                f"{line['lang']}: {line['headline']}"
            )
    if args.update_baseline:
        baselines[name] = {k: v for k, v in scores.items() if isinstance(v, int | float)}
        baseline_file.write_text(json.dumps(baselines, indent=1) + "\n", encoding="utf-8")
        print(f"baseline for {name} updated")
    if problems:
        print("EVAL GATE FAILED:\n  " + "\n  ".join(problems))
        sys.exit(1)
    print("eval gate passed")


if __name__ == "__main__":
    main()
