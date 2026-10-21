import json

import jsonschema
import pytest

from conftest import SCHEMA
from smi_agents.follow import follow, insight_stream
from smi_agents.model import StubModel
from smi_agents.workflow import Narrator

pytestmark = pytest.mark.anyio

OVERLAY_SCHEMA = json.loads((SCHEMA / "overlay.schema.json").read_text("utf-8"))


async def test_follow_narrates_moments_and_retracts_withdrawn_ones(http, tools, core):
    stream = core.responses["GET /api/stream/insights"]
    goal = next(
        json.loads(line[5:])
        for line in stream["body"].splitlines()
        if line.startswith("data:") and '"I_0008"' in line
    )
    retraction = {**goal, "status": "retracted"}  # as if the goal were ruled out later
    stream["body"] += f"event: insight\nid: 99\ndata: {json.dumps(retraction)}\n\n"

    posted: list[dict] = []

    async def post(o):
        jsonschema.validate(o, OVERLAY_SCHEMA)
        posted.append(o)

    count = await follow(insight_stream(http), Narrator(tools, StubModel()), post)
    shows = [o for o in posted if o["op"] == "show"]
    important = [
        json.loads(line[5:])
        for line in stream["body"].splitlines()
        if line.startswith("data:")
        and json.loads(line[5:])["importance"] >= 0.6
        and json.loads(line[5:]).get("status") != "retracted"
    ]
    assert count == len(shows) == len(important) > 0
    assert all(o["verified"] for o in shows)
    assert [o["overlay_id"] for o in shows] == [f"O_{n:08d}" for n in range(1, count + 1)]
    goal_overlay = next(o for o in shows if o["insight_id"] == "I_0008")
    assert posted[-1] == {
        **{k: posted[-1][k] for k in ("generated_at",)},
        "overlay_id": goal_overlay["overlay_id"],
        "match_id": "SYN-154",
        "insight_id": "I_0008",
        "op": "retract",
    }
