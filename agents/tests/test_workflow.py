import json

import jsonschema
import pytest

from conftest import SCHEMA
from smi_agents import overlay
from smi_agents.model import ScriptedModel, StubModel
from smi_agents.types import Line, NarrationRequest
from smi_agents.workflow import MAX_ATTEMPTS, Narrator

pytestmark = pytest.mark.anyio

OVERLAY_SCHEMA = json.loads((SCHEMA / "overlay.schema.json").read_text("utf-8"))
WRONG_NUMBER = Line(headline="Liam Carver scores after 7 passes", body="It is 2-1.")
INVENTED_CLAIM = Line(headline="Liam Carver scores a penalty", body="It is 2-1.")
CORRECT = Line(
    headline="Liam Carver finishes a counter for Eastmere Rovers",
    body="Eastmere Rovers scored 11 seconds after winning the ball. It is 2-1.",
)


def valid_overlay(n, number=1) -> dict:
    o = overlay.show(n, overlay.overlay_id(number))
    jsonschema.validate(o, OVERLAY_SCHEMA)
    return o


@pytest.mark.parametrize("insight", ["I_0012", "I_0025", "I_0024", "I_0027"])
async def test_the_stub_gives_verified_lines_in_three_languages(tools, insight):
    narrator = Narrator(tools, StubModel())
    for lang in ("en", "hi", "es"):
        n = await narrator.narrate(NarrationRequest(insight_id=insight, lang=lang))
        assert n.verified and not n.template_fallback, n.trail
        stages = [(a.stage, a.ok) for a in n.trail]
        assert stages == (
            [("narrate", True)] if lang == "en" else [("narrate", True), ("localize", True)]
        )
        o = valid_overlay(n)
        assert (o["lang"], o["insight_id"], o["verified"]) == (lang, insight, True)


async def test_a_rejected_line_is_retried_with_the_reasons(tools):
    model = ScriptedModel({"narrate": [WRONG_NUMBER, INVENTED_CLAIM, CORRECT]})
    n = await Narrator(tools, model).narrate(
        NarrationRequest(insight_id="I_0012", persona="analyst")
    )
    assert (n.verified, n.template_fallback, n.line) == (True, False, CORRECT)
    assert [(a.attempt, a.reasons) for a in n.trail] == [
        (1, ["unsupported_number:7"]),
        (2, ["unsupported_claim:penalty"]),
        (3, []),
    ]
    feedback = [p["feedback"] for task, p in model.calls if task == "narrate"]
    assert feedback == [[], ["unsupported_number:7"], ["unsupported_claim:penalty"]]


async def test_three_failures_fall_back_to_the_template(tools):
    model = ScriptedModel({"narrate": [WRONG_NUMBER] * MAX_ATTEMPTS})
    n = await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012", lang="es"))
    assert n.template_fallback and n.verified
    assert [a.ok for a in n.trail] == [False] * MAX_ATTEMPTS
    assert n.line.headline == "Liam Carver culmina un contraataque de Eastmere Rovers"
    assert valid_overlay(n)["template_fallback"] is True


async def test_unparseable_replies_count_as_rejections(tools):
    model = ScriptedModel({"narrate": ["not json", '{"headline": 5}', CORRECT]})
    n = await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012"))
    assert [a.reasons for a in n.trail][:2] == [
        ["unparseable_reply:JSONDecodeError"],
        ["unparseable_reply:ValidationError"],
    ]
    assert n.verified and not n.template_fallback


async def test_a_bad_translation_retries_localize_then_falls_back(tools):
    untranslated = Line(headline="Liam Carver finishes a counter for Eastmere Rovers")
    model = ScriptedModel({"localize": [untranslated] * MAX_ATTEMPTS})
    n = await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012", lang="hi"))
    assert [(a.stage, a.ok) for a in n.trail] == [("narrate", True)] + [("localize", False)] * 3
    assert all(a.reasons == ["not_hindi"] for a in n.trail[1:])
    assert n.template_fallback and n.verified
    assert "काउंटर" in n.line.headline


async def test_the_translator_gets_the_verified_english_line(tools):
    model = ScriptedModel({"narrate": [CORRECT]})
    await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012", lang="es"))
    localize = [p for task, p in model.calls if task == "localize"]
    assert localize[0]["draft"]["headline"] == CORRECT.headline
    assert localize[0]["lang"] == "es"


async def test_personas_change_the_same_moment(tools):
    narrator = Narrator(tools, StubModel())
    casual = await narrator.narrate(NarrationRequest(insight_id="I_0025"))
    analyst = await narrator.narrate(NarrationRequest(insight_id="I_0025", persona="analyst"))
    home_fan = await narrator.narrate(
        NarrationRequest(insight_id="I_0025", persona="club_fan", favourite_team="T_HOM")
    )
    away_fan = await narrator.narrate(
        NarrationRequest(insight_id="I_0025", persona="club_fan", favourite_team="T_AWY")
    )
    assert analyst.line.body.endswith("Basis: event data, 4 events.")
    assert casual.line.body != analyst.line.body
    assert home_fan.line.body.startswith("A tough moment for Northgate FC")  # their red card
    assert away_fan.line.body.startswith("Good news for Eastmere Rovers fans")
    assert all(n.verified for n in (casual, analyst, home_fan, away_fan))
    press_casual = await narrator.narrate(NarrationRequest(insight_id="I_0024"))
    press_analyst = await narrator.narrate(NarrationRequest(insight_id="I_0024", persona="analyst"))
    # A casual fan gets one simple chip, the analyst four (ADR-021).
    assert (len(press_casual.line.metrics), len(press_analyst.line.metrics)) == (1, 4)


async def test_the_explanation_is_made_once_per_insight_for_every_persona_and_language(tools):
    """D3: why a moment matters is persona-neutral, so one Explain call serves every line of an
    insight. Plain personas see a filtered sheet, so they get their own."""
    model = ScriptedModel({})
    narrator = Narrator(tools, model)
    for persona in ("casual", "club_fan", "analyst", "metric_focus"):
        for lang in ("en", "hi", "es"):
            req = NarrationRequest(insight_id="I_0012", persona=persona, lang=lang)
            await narrator.narrate(req)
    explains = [p for task, p in model.calls if task == "explain"]
    assert [p["request"]["persona"] for p in explains] == ["casual", "analyst"]
    await narrator.narrate(NarrationRequest(insight_id="I_0025"))
    assert len([t for t, _ in model.calls if t == "explain"]) == 3


async def test_results_are_cached_per_insight_persona_and_language(tools, core):
    narrator = Narrator(tools, StubModel())
    req = NarrationRequest(insight_id="I_0012", lang="hi")
    first = await narrator.narrate(req)
    calls = len(core.requests)
    again = await narrator.narrate(req)
    assert again is first and len(core.requests) == calls
    await narrator.narrate(NarrationRequest(insight_id="I_0012", lang="es"))
    assert len(core.requests) > calls


async def test_agents_reach_the_core_only_through_small_tool_calls(tools, core):
    await Narrator(tools, StubModel()).narrate(NarrationRequest(insight_id="I_0024", lang="es"))
    assert [name for name, _ in tools.calls] == ["get_insight", "match_info", "team_stats"]
    assert all(r.startswith("GET /api/tools/") for r in core.requests)


async def test_a_plain_persona_model_sees_only_whole_simple_numbers(tools):
    """ADR-021: the casual fan's model is not shown xG, rates or decimals, so it cannot write
    them; the analyst's model sees the full sheet."""
    model = ScriptedModel({})
    await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0027"))
    sheet = next(p["sheet"] for task, p in model.calls if task == "narrate")
    shown = {**sheet["numbers"], **sheet["insight"]["facts"]}
    assert "xg" not in shown and sheet["metrics"] == []
    assert all(v == int(v) for v in shown.values() if isinstance(v, float)), shown
    assert shown["ctx_minutes_left"] == 9 and shown["ctx_game_state"] == "level"

    model = ScriptedModel({})
    await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0027", persona="analyst"))
    sheet = next(p["sheet"] for task, p in model.calls if task == "narrate")
    assert sheet["numbers"]["xg"] == 0.172


async def test_a_plain_persona_gets_code_chosen_chips(tools):
    """Whatever chips the model writes for a casual fan, code sets one simple count."""
    chips = CORRECT.model_copy(update={"metrics": [{"label": "xG", "value": 0.31}]})
    model = ScriptedModel({"narrate": [chips]})
    n = await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012"))
    assert n.verified and [a.ok for a in n.trail] == [True]
    assert [(m.label, m.value) for m in n.line.metrics] == [("Passes in the move", 4)]


async def test_a_score_chip_is_dropped_not_rejected(tools):
    """The online eval's analysts got a "Score 1-2" chip from the model, read as a goal claim
    and a wrong number. The score belongs in the body, so code drops the chip."""
    chips = [{"label": "Score (NOR-EAS)", "value": 102}, {"label": "Passes", "value": 4}]
    model = ScriptedModel({"narrate": [CORRECT.model_copy(update={"metrics": chips})]})
    n = await Narrator(tools, model).narrate(
        NarrationRequest(insight_id="I_0012", persona="analyst")
    )
    assert n.verified and [a.ok for a in n.trail] == [True], n.trail
    assert [m.label for m in n.line.metrics] == ["Passes"]


async def test_the_full_time_recap_becomes_a_recap_card(tools):
    """ADR-023: the recap is a full-width recap_card that stays up as long as an overlay may."""
    for persona in ("casual", "analyst"):
        n = await Narrator(tools, StubModel()).narrate(
            NarrationRequest(insight_id="I_0035", persona=persona)
        )
        assert n.verified and not n.template_fallback, n.trail
        o = valid_overlay(n)
        assert (o["type"], o["style"], o["duration_ms"]) == (
            "recap_card",
            "full_width_banner",
            30000,
        )
        assert o["content"]["headline"].startswith("Full time:")
