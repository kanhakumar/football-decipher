import pytest

from smi_agents.types import Line, LineMetric
from smi_agents.verifier import verify

pytestmark = pytest.mark.anyio


def reasons(line: Line, sheet, lang="en") -> list[str]:
    return verify(line, sheet, lang).reasons


async def test_a_correct_line_is_accepted(sheets):
    goal = sheets["goal"]  # Carver's counter goal, 33', 2-1, 4 passes in 11.4 s, xG 0.102
    line = Line(
        headline="Liam Carver finishes a counter for Eastmere Rovers",
        body="Eastmere Rovers won the ball and scored 11 seconds later after 4 passes. "
        "It is 2-1 in minute 33, from a chance worth 0.10 xG.",
        metrics=[LineMetric(label="xG", value=0.1), LineMetric(label="Passes", value=4)],
    )
    assert verify(line, goal, "en").ok, reasons(line, goal)


async def test_a_wrong_number_is_rejected(sheets):
    goal = sheets["goal"]
    line = Line(headline="Liam Carver scores after 7 passes", body="It is 2-1.")
    assert reasons(line, goal) == ["unsupported_number:7"]
    wrong_xg = Line(headline="Carver scores", body="A chance worth 0.31 xG.")
    assert reasons(wrong_xg, goal) == ["unsupported_number:0.31"]
    wrong_metric = Line(headline="Carver scores", metrics=[LineMetric(label="xG", value=0.5)])
    assert reasons(wrong_metric, goal) == ["unsupported_metric:xG=0.5"]


async def test_number_words_and_wrong_minutes_are_numbers_too(sheets):
    goal = sheets["goal"]
    assert reasons(Line(headline="Carver scores after seven passes"), goal) == [
        "unsupported_number:seven"
    ]
    assert reasons(Line(headline="Carver scores after four passes"), goal) == []
    # 11 is a fact (seconds), but not a minute of this insight's evidence
    assert reasons(Line(headline="Carver scores in the 11' minute"), goal) == [
        "unsupported_minute:11"
    ]


async def test_unsupported_claims_are_rejected(sheets):
    goal, red = sheets["goal"], sheets["red"]
    assert reasons(Line(headline="Carver scores a penalty"), goal) == ["unsupported_claim:penalty"]
    assert reasons(Line(headline="Carver heads in a header"), goal) == ["unsupported_claim:header"]
    assert reasons(Line(headline="Carver scores, then gets a red card"), goal) == [
        "unsupported_claim:red_card"
    ]
    assert reasons(Line(headline="Eastmere Rovers equalise"), goal) == [
        "unsupported_claim:equaliser"
    ]
    assert reasons(Line(headline="Northgate FC score from the restart"), red) == [
        "unsupported_claim:goal"
    ]


async def test_names_must_belong_to_the_moment(sheets):
    goal = sheets["goal"]
    # Tomas Brandt (home keeper) is not in this insight or its evidence
    assert reasons(Line(headline="Carver beats Tomas Brandt"), goal) == [
        "unsupported_name:Brandt",
        "unsupported_name:Tomas Brandt",
    ]
    assert reasons(Line(headline="Carver scores for P_AWY_09"), goal) == ["raw_id:P_AWY_09"]


async def test_rounding_and_percentages(sheets):
    press = sheets["press"]  # after_rate 0.556, before_rate 0.333
    ok = Line(headline="Northgate FC press", body="0.56 per pass, up from 0.33; that is 56% now.")
    assert reasons(ok, press) == []
    assert reasons(Line(headline="Pressing at 0.6 per pass"), press) == []  # 0.556 rounds to 0.6
    assert reasons(Line(headline="Pressing at 0.7 per pass"), press) == ["unsupported_number:0.7"]
    assert reasons(Line(headline="Pressing on 61% of passes"), press) == ["unsupported_number:61"]


async def test_hindi_and_spanish(sheets):
    red = sheets["red"]  # 76', 10 players, 1-2
    hi = Line(
        headline="Northgate FC के अब १० खिलाड़ी", body="मिनट 76 में Ruben Castell को लाल कार्ड मिला।"
    )
    assert reasons(hi, red, "hi") == []
    assert reasons(Line(headline="Northgate FC के अब ९ खिलाड़ी"), red, "hi") == [
        "unsupported_number:9"
    ]
    assert reasons(Line(headline="Northgate FC down to 10"), red, "hi") == ["not_hindi"]
    es = Line(headline="Northgate FC se queda con diez", body="Expulsado en el minuto 76.")
    assert reasons(es, red, "es") == []
    assert reasons(Line(headline="Penalti y roja para Northgate FC"), red, "es") == [
        "unsupported_claim:penalty"
    ]


async def test_length_limits(sheets):
    goal = sheets["goal"]
    line = Line(headline="x" * 121, body="y" * 401, metrics=[LineMetric(label="m", value=4)] * 5)
    assert reasons(line, goal) == [
        "body_too_long:401",
        "headline_too_long:121",
        "too_many_metrics:5",
    ]
