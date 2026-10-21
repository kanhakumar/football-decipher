import pytest

from conftest import all_insight_ids
from smi_agents import templates
from smi_agents.facts import gather
from smi_agents.types import LANGS, NarrationRequest
from smi_agents.verifier import verify

pytestmark = pytest.mark.anyio

# Every reason code the core's detectors emit (core/src/main/scala/smi/insight/Detectors.scala).
REASON_CODES = {
    "counter_attack_goal", "equaliser", "takes_lead", "extends_lead", "pulls_one_back",
    "down_to_ten", "down_to_nine", "big_chance_missed", "big_chance_saved",
    "momentum_swings_to_team", "high_press_spell", "pressing_raises_chance_quality",
    "game_turns_chaotic", "team_takes_control", "tempo_rises", "tempo_drops",
    "player_pass_milestone", "brace", "hat_trick", "team_shot_milestone",
}  # fmt: skip

PERSONAS = [
    NarrationRequest(insight_id="I_1", persona="casual"),
    NarrationRequest(insight_id="I_1", persona="analyst"),
    NarrationRequest(insight_id="I_1", persona="club_fan", favourite_team="T_HOM"),
    NarrationRequest(insight_id="I_1", persona="club_fan", favourite_team="T_AWY"),
    NarrationRequest(insight_id="I_1", persona="player_focus", favourite_player="P_HOM_09"),
    NarrationRequest(insight_id="I_1", persona="metric_focus", metric_focus="xg"),
]


def test_every_reason_code_has_a_template_in_every_language():
    assert templates.codes() == REASON_CODES
    for code, entry in templates.table()["codes"].items():
        assert set(entry) == set(LANGS), code
        for lang in LANGS:
            assert entry[lang]["headline"] and entry[lang]["body"], (code, lang)


async def test_every_template_line_of_the_demo_match_passes_the_verifier(tools, core):
    failures = []
    for iid in all_insight_ids(core):
        sheet = await gather(tools, iid)
        for req in PERSONAS:
            for lang in LANGS:
                line = templates.render(sheet, req, lang)
                verdict = verify(line, sheet, lang)
                if not verdict.ok:
                    failures.append((iid, sheet.code, lang, req.persona, verdict.reasons))
    assert failures == []


async def test_metric_cards_follow_the_persona(sheets):
    press = sheets["press"]
    focus = NarrationRequest(insight_id="I_1", persona="metric_focus", metric_focus="ppda")
    cards = templates.metrics(press, focus, "en")
    assert cards[0].label == "PPDA" and len(cards) == 3
    analyst = templates.metrics(press, NarrationRequest(insight_id="I_1", persona="analyst"), "es")
    assert len(analyst) == 4 and analyst[0].label == "Presión antes"
