"""The Verifier (D7): code, never a model. Every number, name and event claim in a line must be
backed by the FactSheet, which holds only what the MCP tools returned (rule 3).

A number is allowed when it is a correct rounding of a fact (0.172 -> 0.17 or 0.2), a share shown
as a percentage (0.684 -> 68%), or a display minute of the evidence. A name is allowed when it
belongs to the insight's teams or players or to an evidence event. An event claim (penalty, red
card, equaliser, ...) is allowed when the insight kind, reason code, evidence types or facts
support it.
"""

import re
from collections.abc import Iterable

from smi_agents import templates
from smi_agents.types import FactSheet, Lang, Line, Verdict

HEADLINE_MAX = 120
BODY_MAX = 400
METRICS_MAX = 4

_DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
_NUMBER = re.compile(r"(?<![\w.,])(\d+(?:[.,]\d{1,3})?)(?![\d])")
_PERCENT_AFTER = re.compile(r"^\s*(?:%|per ?cent\b|por ciento\b|प्रतिशत)")
_MINUTE_AFTER = re.compile(r"^(?:'|′|’|\s*(?:min\b|minute\b|minuto\b|मिनट))")
_RAW_ID = re.compile(r"\b[TPEIO]_[A-Z0-9_]{1,24}\b")

# Spelled-out numbers (two and up: "one"/"una"/"एक" double as articles).
_NUMBER_WORDS: dict[Lang, dict[str, int]] = {
    "en": dict(
        two=2,
        three=3,
        four=4,
        five=5,
        six=6,
        seven=7,
        eight=8,
        nine=9,
        ten=10,
        eleven=11,
        twelve=12,
        twenty=20,
    ),
    "es": dict(
        dos=2,
        tres=3,
        cuatro=4,
        cinco=5,
        seis=6,
        siete=7,
        ocho=8,
        nueve=9,
        diez=10,
        once=11,
        doce=12,
        veinte=20,
    ),
    "hi": {
        "दो": 2,
        "तीन": 3,
        "चार": 4,
        "पाँच": 5,
        "पांच": 5,
        "छह": 6,
        "सात": 7,
        "आठ": 8,
        "नौ": 9,
        "दस": 10,
        "ग्यारह": 11,
        "बारह": 12,
        "बीस": 20,
    },
}
# Phrases where a number word is not a quantity claim.
_NUMBER_WORD_IDIOMS = re.compile(
    r"\b(?:two-way|second half|first half|twice as|one-two)\b", re.IGNORECASE
)

# Whole-word guards for Devanagari (\b does not treat vowel signs as word characters).
_NOT_DEV = r"(?<![ऀ-ॿ])"
_NOT_DEV_AFTER = r"(?![ऀ-ॿ])"

# Event claims: tag -> (patterns per language, support tokens of which any one suffices).
_CLAIMS: dict[str, tuple[dict[Lang, list[str]], set[str]]] = {
    "goal": (
        {
            "en": [r"\bgoals?\b", r"\bscor(?:e|es|ed|ing|er)\b", r"\bnets?\b", r"\bnetted\b"],
            "es": [r"\bgol(?:es)?\b", r"\bmarc(?:a|ó|an|ar|ador)\b", r"\banot(?:a|ó)\b"],
            "hi": [r"गोल(?!कीपर)"],
        },
        {"goal", "brace", "hat_trick"},
    ),
    "penalty": (
        {
            "en": [r"\bpenalt(?:y|ies)\b", r"\bspot[- ]kick\b"],
            "es": [r"\bpenalti\b", r"\bpenal\b"],
            "hi": [r"पेनल्टी"],
        },
        {"situation=penalty", "penalty"},
    ),
    "red_card": (
        {
            "en": [
                r"\bred card\b",
                r"\bsent off\b",
                r"\bdismiss",
                r"\b(?:ten|10|nine|9)[- ]m[ae]n\b",
            ],
            "es": [r"\btarjeta roja\b", r"\bexpuls", r"\broja\b"],
            "hi": [r"लाल कार्ड", r"बाहर भेज"],
        },
        {"red_card"},
    ),
    "yellow_card": (
        {
            "en": [r"\byellow card\b", r"\bbooked\b", r"\bbooking\b"],
            "es": [r"\btarjeta amarilla\b", r"\bamonest"],
            "hi": [r"पीला कार्ड"],
        },
        {"second_yellow", "yellow_card"},
    ),
    "own_goal": (
        {"en": [r"\bown goal\b"], "es": [r"\bautogol\b", r"\bpropia puerta\b"], "hi": [r"आत्मघाती"]},
        {"own_goal"},
    ),
    "header": (
        {"en": [r"\bheader\b", r"\bheaded\b"], "es": [r"\bcabez"], "hi": [r"हेडर"]},
        {"body_part=head"},
    ),
    "free_kick": (
        {"en": [r"\bfree[- ]kick\b"], "es": [r"\btiro libre\b"], "hi": [r"फ्री किक"]},
        {"free_kick", "situation=free_kick"},
    ),
    "corner": (
        {
            "en": [r"\bcorners?\b"],
            "es": [r"\bc[óo]rner\b", r"\bsaque de esquina\b"],
            "hi": [r"कॉर्नर"],
        },
        {"corner", "situation=corner"},
    ),
    "offside": (
        {"en": [r"\boffside\b"], "es": [r"\bfuera de juego\b"], "hi": [r"ऑफसाइड"]},
        {"offside"},
    ),
    "substitution": (
        {
            "en": [r"\bsubstitut", r"\bcame on\b", r"\bcomes on\b", r"\bsub\b"],
            "es": [r"\bsustitu", r"\bsuplente\b"],
            "hi": [r"सब्स्टीट्यूट", r"प्रतिस्थापन"],
        },
        {"substitution"},
    ),
    "injury": (
        {"en": [r"\binjur"], "es": [r"\blesi[óo]n\b", r"\blesionad"], "hi": [r"चोट"]},
        {"injury"},
    ),
    "var": ({"en": [r"\bVAR\b"], "es": [r"\bVAR\b"], "hi": [r"\bVAR\b"]}, {"var"}),
    "hat_trick": (
        {
            "en": [r"\bhat[- ]trick\b"],
            "es": [r"\btriplete\b", r"\bhat[- ]trick\b"],
            "hi": [r"हैट्रिक"],
        },
        {"hat_trick"},
    ),
    "brace": (
        {"en": [r"\bbrace\b"], "es": [r"\bdoblete\b"], "hi": [r"दूसरा गोल"]},
        {"brace", "hat_trick"},
    ),
    "equaliser": (
        {
            "en": [
                r"\bequalis",
                r"\bequaliz",
                r"\blevels? (?:it|the score|things)\b",
                r"\bdraw level\b",
                r"\bback on (?:level )?terms\b",
                r"\ball square\b",
            ],
            "es": [r"\bempat", r"\biguala"],
            "hi": [r"बराबर"],
        },
        {"equaliser", "equalising_goal"},
    ),
    "lead": (
        {
            "en": [
                r"\b(?:take|takes|took|taking|extend|extends|extended|regain|regains) the lead\b",
                r"\bin front\b",
                r"\bahead\b",
            ],
            "es": [r"\bventaja\b", r"\bpor delante\b", r"\badelant"],
            "hi": [r"बढ़त"],
        },
        {"score=lead"},
    ),
    "trailing": (
        {
            "en": [r"\bbehind\b", r"\btrailing\b", r"\btrail\b"],
            "es": [r"\bpor detrás\b", r"\bperdiendo\b"],
            "hi": [r"पीछे"],
        },
        {"score=trail"},
    ),
    "counter": (
        {
            "en": [r"\bcounter(?:[- ]?attack(?:ing)?)?\b", r"\bon the break\b"],
            "es": [r"\bcontraataque\b", r"\bcontragolpe\b", r"\ba la contra\b"],
            "hi": [r"काउंटर", r"जवाबी हमल"],
        },
        {"counter_attack", "counter_attack_goal"},
    ),
    "save": (
        {
            "en": [r"\bsaved?\b", r"\bsaves\b", r"\bkeeper\b", r"\bgoalkeeper\b"],
            "es": [r"\bparad[ao]\b", r"\batajad", r"\bportero\b"],
            "hi": [r"बचा", r"गोलकीपर"],
        },
        {"outcome=saved", "shot_outcome=saved", "big_chance_saved", "goalkeeper"},
    ),
    "result": (
        {
            "en": [r"\bvictory\b", r"\bwins? the (?:match|game)\b", r"\bdefeat\b"],
            "es": [r"\bvictoria\b", r"\bderrota\b"],
            "hi": [rf"{_NOT_DEV}जीत{_NOT_DEV_AFTER}", rf"{_NOT_DEV}हार{_NOT_DEV_AFTER}"],
        },
        {"full_time"},
    ),
}


# Phrases about the match situation, not about an event in this moment ("a goal down", "the
# score", "chasing an equaliser"). With the match context in the facts (ADR-017) they are
# removed before the event claims are checked, so "Northgate score" still needs a goal.
_CONTEXT_PHRASES: dict[Lang, list[str]] = {
    "en": [
        r"\b(?:a|one|two|three|\d) goals? (?:down|behind|up|ahead|clear|to the good)\b",
        r"\bthe score(?:line)?\b",
        r"\b(?:chas\w*|need\w*|push\w* for|search\w* for|hunt\w* for|look\w* for|go\w* for)"
        r" (?:an|the) equali[sz]er\b",
        r"\bgoal difference\b",
    ],
    "es": [
        r"\b(?:un|dos|tres|\d) gol(?:es)? "
        r"(?:abajo|arriba|por debajo|por encima|de desventaja|de ventaja)\b",
        r"\bel marcador\b",
        r"\b(?:busca\w*|necesita\w*|persigue\w*|en busca de|a por) (?:el|un) empate\b",
    ],
    "hi": [
        r"(?:एक|दो|तीन|\d) गोल (?:से )?(?:पीछे|आगे)",
        r"बराबरी (?:की तलाश|के लिए|का गोल ढूंढ)",
    ],
}


def _texts(line: Line) -> list[str]:
    return [line.headline, line.body, *(f"{m.label} {m.value}" for m in line.metrics)]


def _allowed_number(value: float, decimals: int, percent: bool, facts: Iterable[float]) -> bool:
    tolerance = 0.5 * 10**-decimals + 1e-9
    for v in facts:
        # A fraction may be shown as a percentage; a value already in percent may not be scaled.
        candidates = [v, v * 100] if 0 < v <= 1 else [v]
        if any(abs(value - c) <= tolerance for c in candidates):
            return True
    return False


def _numbers(text: str, lang: Lang) -> list[tuple[float, int, bool, bool, str]]:
    """(value, decimals, percent?, minute mark?, as written) for every number in the text."""
    found = []
    for m in _NUMBER.finditer(text):
        raw = m.group(1).replace(",", ".")
        rest = text[m.end() :]
        decimals = len(raw.split(".")[1]) if "." in raw else 0
        found.append(
            (
                float(raw),
                decimals,
                bool(_PERCENT_AFTER.match(rest)),
                bool(_MINUTE_AFTER.match(rest)),
                m.group(0),
            )
        )
    cleaned = _NUMBER_WORD_IDIOMS.sub(" ", text)
    for word, n in _NUMBER_WORDS[lang].items():
        pattern = rf"(?<!\w){re.escape(word)}(?!\w)"
        for _ in re.finditer(pattern, cleaned, flags=re.IGNORECASE):
            found.append((float(n), 0, False, False, word))
    return found


def _aliases(sheet: FactSheet) -> dict[str, str]:
    """Display alias -> entity id: full names, surnames, team short names and first words."""
    out: dict[str, str] = {}
    for eid, name in sheet.roster.items():
        out[name] = eid
        parts = name.split()
        if eid.startswith("P_") and len(parts) > 1:
            out.setdefault(parts[-1], eid)
        if eid.startswith("T_") and len(parts[0]) > 3:
            out.setdefault(parts[0], eid)
    for eid, short in sheet.team_short.items():
        out[short] = eid
    return out


def verify(line: Line, sheet: FactSheet, lang: Lang) -> Verdict:
    reasons: list[str] = []
    if not line.headline.strip():
        reasons.append("empty_headline")
    if len(line.headline) > HEADLINE_MAX:
        reasons.append(f"headline_too_long:{len(line.headline)}")
    if len(line.body) > BODY_MAX:
        reasons.append(f"body_too_long:{len(line.body)}")
    if len(line.metrics) > METRICS_MAX:
        reasons.append(f"too_many_metrics:{len(line.metrics)}")
    if lang == "hi" and not re.search(r"[ऀ-ॿ]", line.headline + line.body):
        reasons.append("not_hindi")

    texts = [line.headline, line.body]
    facts = list(sheet.numbers.values())
    minutes = {sheet.minute, *sheet.minutes}
    minute_like = [*minutes, *(v for k, v in sheet.numbers.items() if "minute" in k)]
    for text in texts:
        if m := _RAW_ID.search(text):
            reasons.append(f"raw_id:{m.group(0)}")
        normalised = text.translate(_DEVANAGARI_DIGITS)
        for value, decimals, percent, minute_mark, raw in _numbers(normalised, lang):
            pool = minute_like if minute_mark else [*facts, *minutes]
            if not _allowed_number(value, decimals, percent, pool):
                kind = "minute" if minute_mark else "number"
                reasons.append(f"unsupported_{kind}:{raw.strip()}")

    for metric in line.metrics:
        value = metric.value
        if isinstance(value, str):
            try:
                value = float(value.translate(_DEVANAGARI_DIGITS).rstrip("%").replace(",", "."))
            except ValueError:
                reasons.append(f"metric_not_numeric:{metric.label}")
                continue
        decimals = len(str(value).split(".")[1].rstrip("0")) if "." in str(value) else 0
        percent = metric.unit == "%"
        if not _allowed_number(float(value), decimals, percent, facts):
            reasons.append(f"unsupported_metric:{metric.label}={metric.value}")

    allowed = set(sheet.names)
    for alias, eid in _aliases(sheet).items():
        if eid in allowed:
            continue
        pattern = rf"(?<!\w){re.escape(alias)}(?!\w)"
        if any(re.search(pattern, t) for t in _texts(line)):
            reasons.append(f"unsupported_name:{alias}")

    # The moment belongs to its first team (and its players): the headline must say so, or a
    # line can credit one side with the other side's numbers. Neutral moments are exempt.
    if templates.table()["polarity"].get(sheet.code) != "neutral":
        subject = {sheet.subject_team, *(p.root for p in sheet.insight.players or [])}
        named = (
            eid
            for alias, eid in _aliases(sheet).items()
            if re.search(rf"(?<!\w){re.escape(alias)}(?!\w)", line.headline)
        )
        if not subject & set(named):
            reasons.append("subject_not_in_headline")

    joined = " ".join(_texts(line))
    if "match_context" in sheet.support:
        for phrase in _CONTEXT_PHRASES.get(lang, []):
            joined = re.sub(phrase, " ", joined, flags=re.IGNORECASE)
    for tag, (patterns, needs) in _CLAIMS.items():
        if needs & sheet.support:
            continue
        if any(re.search(p, joined, flags=re.IGNORECASE) for p in patterns.get(lang, [])):
            reasons.append(f"unsupported_claim:{tag}")

    return Verdict(ok=not reasons, reasons=sorted(set(reasons)))
