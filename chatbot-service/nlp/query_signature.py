"""Chữ ký tất định của câu hỏi — khoá phụ của semantic cache.

Đo bằng ``tests/eval/run_cache_pairs.py``: embedding gần như không phân biệt con số, mốc
thời gian và từ so sánh ("SpO2 dưới 95" / "SpO2 trên 95" giống nhau 0.99), nên không
ngưỡng nào vừa giữ được hit đúng vừa chặn được hit nhầm. Chữ ký gom đúng những chi tiết
đó bằng luật; hai câu chỉ được dùng chung câu trả lời khi chữ ký trùng nhau.
"""

from __future__ import annotations

import re
from datetime import date

from nlp.observation_types import infer_bp_component, infer_observation_type
from nlp.text import contains_any, normalize_text
from nlp.time_range import extract_time_range
from nlp.value_filter import extract_value_filter, format_number
from nlp.vocabulary import (
    CONDITION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    EXPLAIN_KEYWORDS,
    LATEST_VISIT_PHRASES,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    TREND_KEYWORDS,
)


# "Lần khám đầu tiên" và "lần khám gần nhất" giống nhau 0.97 về embedding.
_EARLIEST_PATTERN = r"\b(dau tien|lan dau|cu nhat|som nhat|ban dau)\b"
# "3 lần khám gần nhất" và "lần khám gần nhất" cần số bản ghi khác nhau.
_COUNT_PATTERN = r"\b(\d{1,3}) (lan|ban ghi|ket qua|xet nghiem|chi so|don thuoc|thuoc)\b"


def query_signature(message: str, *, today: date | None = None) -> str:
    """Chữ ký đầy đủ cho semantic cache (câu hỏi → câu trả lời): gồm cả giá trị cụ thể."""
    time_range = extract_time_range(message, today=today)
    value_filter = extract_value_filter(message)
    return "|".join(
        [
            *_template_parts(message),
            f"bp={infer_bp_component(message) or '-'}",
            f"time={time_range.date_from or ''}..{time_range.date_to or ''}" if time_range else "time=-",
            f"value={value_filter[0]}{format_number(value_filter[1])}" if value_filter else "value=-",
        ]
    )


def plan_signature(message: str) -> str:
    """Chữ ký "khung" cho plan cache: bỏ khoảng ngày, ngưỡng, tâm thu/tâm trương vì các giá
    trị đó được điền lại bằng luật từ câu hỏi mới (plan_cache.refill_identifiers)."""
    return "|".join(_template_parts(message))


def _template_parts(message: str) -> list[str]:
    text = normalize_text(message or "")
    resources = [
        name
        for name, keywords in (
            ("med", MEDICATION_KEYWORDS),
            ("cond", CONDITION_KEYWORDS),
            ("enc", ENCOUNTER_KEYWORDS),
            ("obs", OBSERVATION_KEYWORDS),
        )
        if contains_any(text, keywords)
    ]
    return [
        f"obs={infer_observation_type(message) or '-'}",
        f"res={','.join(resources) or '-'}",
        f"explain={int(contains_any(text, EXPLAIN_KEYWORDS))}",
        f"trend={int(contains_any(text, TREND_KEYWORDS))}",
        f"visit={int(contains_any(text, LATEST_VISIT_PHRASES))}",
        f"first={int(bool(re.search(_EARLIEST_PATTERN, text)))}",
        f"count={count.group(1) if (count := re.search(_COUNT_PATTERN, text)) else '-'}",
    ]
