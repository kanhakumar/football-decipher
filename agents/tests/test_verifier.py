import pytest

from conftest import all_insight_ids
from smi_agents.facts import gather
from smi_agents.types import Line, LineMetric
from smi_agents.verifier import verify

pytestmark = pytest.mark.anyio


def reasons(line: Line, sheet, lang="en", plain=False) -> list[str]:
    return verify(line, sheet, lang, plain).reasons


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

    def northgate(text: str) -> Line:
        return Line(headline=f"Northgate FC: {text}")

    ok = Line(headline="Northgate FC press", body="0.56 per pass, up from 0.33; that is 56% now.")
    assert reasons(ok, press) == []
    assert reasons(northgate("pressing at 0.6 per pass"), press) == []  # 0.556 rounds to 0.6
    assert reasons(northgate("pressing at 0.7 per pass"), press) == ["unsupported_number:0.7"]
    assert reasons(northgate("pressing on 61% of passes"), press) == ["unsupported_number:61"]
    # spell possession is already a percentage (49.5): it may not be scaled again
    possession = press.numbers["spell_possession_share"]
    assert possession > 1
    assert reasons(northgate(f"{possession:.1f}% possession"), press) == []
    assert reasons(northgate(f"{possession * 100:.0f}% possession"), press) != []


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
        "subject_not_in_headline",
        "too_many_metrics:5",
    ]


async def test_the_headline_must_name_the_moments_team(tools):
    # Seen in the first online eval: Northgate's momentum swing (I_0011) credited to Eastmere.
    from smi_agents.facts import gather

    swing = await gather(tools, "I_0011")
    wrong = Line(headline="Eastmere Rovers take 82% of the actions", body="")
    assert reasons(wrong, swing) == ["subject_not_in_headline"]
    right = Line(headline="Momentum swings to Northgate FC", body="82% of the actions now.")
    assert reasons(right, swing) == []
    # Neutral moments (a tempo change) need no team in the headline.
    tempo = await gather(tools, "I_0007")
    assert reasons(Line(headline="The tempo picks up"), tempo) == []


async def test_the_match_situation_may_be_described_but_not_new_events(sheets):
    press, red = sheets["press"], sheets["red"]  # Northgate trail 1-2 in both
    ok = Line(
        headline="Northgate FC press to get back into it",
        body="A goal down at 1-2, Northgate FC are chasing an equaliser and the score could turn.",
    )
    assert reasons(ok, press) == []
    es = Line(
        headline="Northgate FC aprieta", body="Con un gol abajo, Northgate FC busca el empate."
    )
    assert reasons(es, press, "es") == []
    hi = Line(headline="Northgate FC का दबाव", body="एक गोल से पीछे Northgate FC बराबरी की तलाश में है।")
    assert reasons(hi, press, "hi") == []
    # Event claims still need the evidence: no goal was scored at the red card.
    assert reasons(Line(headline="Northgate FC score from the restart"), red) == [
        "unsupported_claim:goal"
    ]
    assert reasons(Line(headline="Northgate FC equalise"), press) == ["unsupported_claim:equaliser"]


async def test_level_games_chase_phrasing_and_hindi_defending(sheets, tools, core):
    press = sheets["press"]  # Northgate trail 1-2
    chase = Line(headline="Northgate FC press", body="Northgate FC press hard to get back level.")
    assert reasons(chase, press) == []
    es = Line(headline="Northgate FC presiona", body="Northgate FC quiere ir a por el empate.")
    assert reasons(es, press, "es") == []
    defending = Line(headline="Northgate FC का दबाव", body="Eastmere Rovers बचाव कर रही है।")
    assert reasons(defending, press, "hi") == []
    # A spell in a level game (2-2): the score may be called level, in every language.
    level = None
    for iid in all_insight_ids(core):
        sheet = await gather(tools, iid)
        if sheet.code == "pressure_without_reward":
            level = sheet
    assert level is not None
    line = Line(
        headline="Eastmere Rovers dominate",
        body="At 2-2 the game is level; el partido está empatado.",
    )
    assert "unsupported_claim:equaliser" not in reasons(line, level)


async def test_wanting_a_goal_is_the_situation_not_a_goal(sheets):
    press, red = sheets["press"], sheets["red"]  # Northgate trail 1-2
    for body in (
        "Northgate FC push up in search of a goal.",
        "They need a leveller with time running out.",
        "Plenty of pressure, but still without a goal to show for it.",
        "Northgate FC are yet to score since the break.",
    ):
        assert reasons(Line(headline="Northgate FC push on", body=body), press) == [], body
    es = Line(headline="Northgate FC empuja", body="Northgate FC sube líneas en busca del gol.")
    assert reasons(es, press, "es") == []
    hi = Line(headline="Northgate FC का दबाव", body="Northgate FC को गोल की तलाश है।")
    assert reasons(hi, press, "hi") == []
    # Scoring is still an event the evidence must hold.
    for headline in ("Northgate FC score a goal", "Northgate FC find the equaliser"):
        assert reasons(Line(headline=headline), red), headline


async def test_stakes_phrasing_from_the_online_eval_is_the_situation(sheets):
    """Rejected drafts from the online eval (ADR-021) that describe the chase, not an event."""
    press, red = sheets["press"], sheets["red"]  # Northgate trail 1-2
    for body in (
        "Down to 10 with 14 minutes left, making an equaliser much harder now.",
        "This forces Northgate to chase for an equaliser.",
        "They are pushing higher to try to force an equaliser.",
        "They chase a 1-2 score with 14 minutes left.",
        "The press is meant to create scoring chances.",
        "It raises the difficulty of overturning the one-goal deficit.",
        "Lots of the ball, but they fail to test the keeper.",
        "It sharply increases the risk of defeat.",
    ):
        assert reasons(Line(headline="Northgate FC push on", body=body), red) == [], body
    es = Line(
        headline="Northgate FC aprieta",
        body="Northgate FC sigue a un gol en su intento por darle la vuelta al marcador.",
    )
    assert reasons(es, press, "es") == []
    hi = Line(
        headline="Northgate FC का दबाव",
        body="Northgate FC ने टैकल जीत कर दबाव बनाया; अब बराबरी का पीछा है।",
    )
    assert reasons(hi, press, "hi") == []
    # From the recap release's eval: the chase, the time left and equal numbers in words.
    for body in (
        "An aggressive push to find a goal.",
        "They press for an equaliser.",
        "A clear tactical shove to win the match.",
    ):
        assert reasons(Line(headline="Northgate FC push on", body=body), red) == [], body
    for body in (
        "Northgate FC aprieta sin llegar a probar al portero.",
        "Un empuje para buscar el gol de la victoria.",
        "Presionan al final pero no encuentran el gol.",
        "Atacan con un hombre menos para igualar el marcador.",
        "Sube la línea para presionar por el empate, persiguiendo un marcador de 1-2.",
    ):
        assert reasons(Line(headline="Northgate FC aprieta", body=body), red, "es") == [], body
    for body in (
        "Northgate FC के पास 14 मिनट बचे हैं।",
        "बराबर टीमों के बावजूद Northgate FC पीछे है।",
        "Eastmere Rovers के पास संख्यात्मक बढ़त है।",
        "1-2 की हार के दौरान Northgate FC आगे बढ़ी।",
        "Northgate FC ने बराबरी करने के लिए आगे धक्का दिया।",
        "यह शिफ्ट बचा हुआ स्थान छोड़ती है।",
    ):
        assert reasons(Line(headline="Northgate FC का दबाव", body=body), red, "hi") == [], body
    goal = sheets["goal"]  # 1-1 to 2-1: from level to ahead is the goal itself
    es_goal = Line(
        headline="Liam Carver adelanta a Eastmere Rovers",
        body="El contraataque cambió el partido de empate a ventaja: 2-1.",
    )
    assert reasons(es_goal, goal, "es") == []
    equaliser = sheets["equaliser"]
    es_eq = Line(headline="Kofi Mensah iguala", body="El gol anuló la ventaja de Eastmere Rovers.")
    assert "unsupported_claim:lead" not in reasons(es_eq, equaliser, "es")
    for lang, headline in (
        ("es", "Northgate FC empata el partido"),
        ("es", "Northgate FC iguala el marcador"),
        ("hi", "Northgate FC ने बराबरी कर ली"),
    ):
        assert reasons(Line(headline=headline), red, lang), headline
    # Still claims: an equaliser, a save and a win need the evidence.
    for headline in (
        "Northgate FC equalise",
        "The keeper saves from Kofi Mensah",
        "Northgate FC win the game",
    ):
        assert reasons(Line(headline=headline), red), headline


async def test_plain_personas_get_no_technical_terms(sheets):
    goal = sheets["goal"]
    technical = Line(
        headline="Liam Carver finishes a counter", body="A chance worth 0.10 xG makes it 2-1."
    )
    assert reasons(technical, goal) == []  # fine for the analyst
    assert reasons(technical, goal, plain=True) == ["technical_term:decimal", "technical_term:xG"]
    press = Line(headline="Northgate FC press", body="Northgate FC pressed harder; PPDA fell.")
    assert "technical_term:PPDA" in reasons(press, sheets["press"], plain=True)
    plain = Line(
        headline="Liam Carver finishes a counter",
        body="Eastmere Rovers lead 2-1 after a quick break.",
    )
    assert reasons(plain, goal, plain=True) == []
