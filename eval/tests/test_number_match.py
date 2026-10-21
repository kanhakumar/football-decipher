import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from number_match import check, line_numbers, tool_numbers  # noqa: E402

FACTS = {"minute": 33, "team_goals": 2, "opponent_goals": 1, "xg": 0.102}
GOAL = {
    "facts": FACTS | {"possession_duration_s": 11.4, "possession_passes": 4, "after_share": 0.684}
}


def card(headline, body="", metrics=()):
    return {"content": {"headline": headline, "body": body, "metrics": list(metrics)}}


def test_a_clean_line_passes():
    line = card(
        "Carver finishes a counter",
        "Scored 11 seconds after winning the ball, after 4 passes. It is 2-1 in minute 33.",
        [{"label": "xG", "value": 0.1}],
    )
    assert check(line, [GOAL]) == []


def test_a_wrong_number_is_caught():
    assert check(card("Carver scores after 7 passes"), [GOAL]) == ["7"]
    assert check(card("A chance worth 0.31 xG"), [GOAL]) == ["0.31"]
    assert check(card("x", metrics=[{"label": "xG", "value": 0.5}]), [GOAL]) == ["0.5"]


def test_rounding_percentages_and_scripts():
    assert check(card("68% of the actions"), [GOAL]) == []
    assert check(card("68.4% of the actions"), [GOAL]) == []
    assert check(card("69% of the actions"), [GOAL]) == ["69"]
    assert check(card("मिनट ३३ में गोल"), [GOAL]) == []  # Devanagari digits
    assert check(card("Va 2-1, minuto 33, 0,10 xG"), [GOAL]) == []  # decimal comma


def test_tool_answers_are_walked_but_strings_and_flags_are_not_numbers():
    answer = {"metrics": [{"metric": "xg", "value": 1.27, "basis": "event_data"}], "ok": True}
    assert list(tool_numbers(answer)) == [1.27]
    assert [v for _, v, _ in line_numbers(card("P_HOM_09 at 1.27"))] == [1.27]
