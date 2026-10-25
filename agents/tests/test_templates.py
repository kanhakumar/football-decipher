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
    # Context-aware codes and the newer detectors (ADR-017).
    "ten_men_equalise", "late_equaliser", "late_lead", "ten_men_take_control",
    "pressure_without_reward", "press_while_chasing", "leaders_keep_the_ball",
    "pass_creates_big_chance", "creator_in_form", "line_breaking_pass",
    "sits_deep_to_protect_lead", "team_sits_deeper", "pushes_up_to_chase", "team_pushes_higher",
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
                verdict = verify(line, sheet, lang, templates.is_plain(req.persona))
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
    possession = NarrationRequest(
        insight_id="I_1", persona="metric_focus", metric_focus="possession_share"
    )
    card = templates.metrics(press, possession, "en")[0]
    assert (card.label, card.unit) == ("Possession", "%")
    assert card.value == round(press.numbers["spell_possession_share"], 1)


async def test_plain_personas_get_the_situation_not_the_statistics(sheets):
    goal = sheets["goal"]  # Eastmere Rovers 2-1 up after the 33' counter
    casual = templates.render(goal, NarrationRequest(insight_id="I_1", persona="casual"), "en")
    analyst = templates.render(goal, NarrationRequest(insight_id="I_1", persona="analyst"), "en")
    assert casual.headline == analyst.headline
    assert casual.body.startswith("Eastmere Rovers lead 2-1 with ")
    assert "xG" not in casual.body and "seconds" in analyst.body
    assert len(casual.metrics) <= 1 and all(m.label != "xG" for m in casual.metrics)
    assert len(analyst.metrics) == 4
    red = sheets["red"]  # Northgate down to ten, 1-2
    es = templates.render(red, NarrationRequest(insight_id="I_1", persona="casual"), "es")
    assert es.body == "Northgate FC pierde 1-2 a falta de 14 minutos, y con uno menos."
