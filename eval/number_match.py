"""The number-match check: an independent second opinion on the Verifier (rule 3).

Every number in a published line must equal a number that the core's tools returned for that
moment. This checker shares no code with `smi_agents.verifier`: it reads the raw JSON the tools
answered and the overlay as published, nothing in between.

A number matches a tool value when it is a correct rounding of it at the precision written
(0.172 -> 0.17 or 0.2), or a fraction shown as a percentage (0.684 -> 68 or 68.4).
"""

import re
from collections.abc import Iterable, Iterator
from typing import Any

_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
_NUMBER = re.compile(r"(?<![\w.,])(\d+(?:[.,]\d+)?)(?!\d)")


def tool_numbers(answer: Any) -> Iterator[float]:
    """Every number anywhere in a tool answer (ids and other strings are skipped)."""
    if isinstance(answer, bool):
        return
    if isinstance(answer, int | float):
        yield float(answer)
    elif isinstance(answer, dict):
        for v in answer.values():
            yield from tool_numbers(v)
    elif isinstance(answer, list):
        for v in answer:
            yield from tool_numbers(v)


def line_numbers(overlay: dict[str, Any]) -> list[tuple[str, float, int]]:
    """(as written, value, decimals) for every number in a published overlay's text and metrics."""
    content = overlay.get("content", {})
    texts = [content.get("headline", ""), content.get("body", "")]
    texts += [str(m.get("value", "")) for m in content.get("metrics", [])]
    out = []
    for text in texts:
        for m in _NUMBER.finditer(text.translate(_DIGITS)):
            raw = m.group(1).replace(",", ".")
            decimals = len(raw.split(".")[1]) if "." in raw else 0
            out.append((m.group(1), float(raw), decimals))
    return out


def matches(value: float, decimals: int, facts: Iterable[float]) -> bool:
    tolerance = 0.5 * 10**-decimals + 1e-9
    for f in facts:
        if abs(value - f) <= tolerance:
            return True
        if 0 < f <= 1 and abs(value - f * 100) <= tolerance:
            return True
    return False


def check(overlay: dict[str, Any], answers: Iterable[Any]) -> list[str]:
    """The numbers in `overlay` that no tool answer backs (empty when the line is clean)."""
    facts = [n for a in answers for n in tool_numbers(a)]
    return [
        raw for raw, value, decimals in line_numbers(overlay) if not matches(value, decimals, facts)
    ]
