import pytest

from smi_agents.facts import ToolError

pytestmark = pytest.mark.anyio

TOOLS = {
    "match_state",
    "match_info",
    "team_stats",
    "player_stats",
    "player_workload",
    "team_shape",
    "recent_events",
    "list_insights",
    "get_insight",
}


async def test_one_tool_per_core_endpoint_with_typed_arguments(mcp):
    listed = {t.name: t for t in (await mcp.list_tools()).tools}
    assert set(listed) == TOOLS
    stats = listed["team_stats"].input_schema
    assert stats["required"] == ["team"]
    assert stats["properties"]["team"]["pattern"] == r"^T_[A-Z0-9_]{1,16}$"
    n = listed["recent_events"].input_schema["properties"]["n"]
    assert (n["minimum"], n["maximum"]) == (1, 50)
    assert all(t.description for t in listed.values())


async def test_tools_return_the_core_json_unchanged(tools, core):
    info = await tools.call("match_info", {})
    assert info == core.responses["GET /api/tools/match_info"]["body"]
    assert [t["name"] for t in info["teams"]] == ["Northgate FC", "Eastmere Rovers"]
    events = await tools.call("recent_events", {"n": 5})
    assert events["count"] == 5
    state = await tools.call("match_state", {})
    assert state["score"] == {"T_HOM": 2, "T_AWY": 2}


async def test_core_errors_become_tool_errors(tools):
    with pytest.raises(ToolError, match="unknown team 'T_NOPE'"):
        await tools.call("team_stats", {"team": "T_NOPE"})
    with pytest.raises(ToolError, match="not recorded"):
        await tools.call("get_insight", {"insight_id": "I_9999"})


async def test_arguments_are_validated_before_the_core_is_called(tools, core):
    with pytest.raises(ToolError):
        await tools.call("recent_events", {"n": 500})
    with pytest.raises(ToolError):
        await tools.call("team_stats", {"team": "home"})
    assert core.requests == []
