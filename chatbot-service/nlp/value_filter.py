"""Nhận diện điều kiện ngưỡng trong câu hỏi: "HbA1c > 7", "SpO2 dưới 95", "từ 140 trở lên".

Dùng cho tool ``get_all_patient_observations``: validator điền ``value_op``/``value`` khi
planner bỏ sót, và chuẩn hoá giá trị planner sinh ra (">", "lớn hơn" → ``gt``).
"""

from __future__ import annotations

import re
from typing import Any

from nlp.text import normalize_text


VALUE_OPS = ("gt", "ge", "lt", "le")
VALUE_OP_SYMBOLS = {"gt": ">", "ge": "≥", "lt": "<", "le": "≤"}

_NUM = r"(\d+(?:[.,]\d+)?)"
# Số đứng trước các từ này là số đếm / tuổi / thời gian, không phải ngưỡng chỉ số.
_NOT_A_THRESHOLD = r"\s*(tuoi|lan|nam|thang|ngay|tuan|benh nhan|nguoi|ban ghi|don|loai)\b"

_SYMBOL_PATTERNS = (
    ("ge", rf"(?:>=|≥|=>)\s*{_NUM}"),
    ("le", rf"(?:<=|≤|=<)\s*{_NUM}"),
    ("gt", rf">\s*{_NUM}"),
    ("lt", rf"<\s*{_NUM}"),
)
_TEXT_PATTERNS = (
    ("ge", rf"\b(?:lon hon hoac bang|cao hon hoac bang|tu)\s+{_NUM}\s*\S*\s*tro len\b"),
    ("ge", rf"\b(?:lon hon hoac bang|cao hon hoac bang|it nhat|toi thieu)\s+{_NUM}"),
    ("le", rf"\b(?:nho hon hoac bang|thap hon hoac bang|khong qua|khong vuot qua|toi da)\s+{_NUM}"),
    ("le", rf"\b{_NUM}\s*\S*\s*tro xuong\b"),
    ("gt", rf"\b(?:lon hon|cao hon|tren|vuot qua|vuot nguong|vuot|greater than|above|over)\s+{_NUM}"),
    ("lt", rf"\b(?:nho hon|thap hon|duoi|be hon|kem hon|less than|below|under)\s+{_NUM}"),
)

_OP_ALIASES = {
    ">": "gt", "gt": "gt", "greater": "gt", "lon hon": "gt", "tren": "gt",
    ">=": "ge", "≥": "ge", "ge": "ge", "gte": "ge",
    "<": "lt", "lt": "lt", "less": "lt", "nho hon": "lt", "duoi": "lt",
    "<=": "le", "≤": "le", "le": "le", "lte": "le",
}


def extract_value_filter(message: str) -> tuple[str, float] | None:
    raw = message or ""
    for op, pattern in _SYMBOL_PATTERNS:
        match = re.search(pattern, raw)
        if match and not _followed_by_count(raw, match.end()):
            return op, _to_float(match.group(1))

    text = normalize_text(raw)
    for op, pattern in _TEXT_PATTERNS:
        for match in re.finditer(pattern, text):
            if _followed_by_count(text, match.end()):
                continue
            return op, _to_float(match.group(1))
    return None


def normalize_value_op(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    return _OP_ALIASES.get(normalize_text(value).strip())


def normalize_threshold(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str) and re.fullmatch(r"\s*-?\d+(?:[.,]\d+)?\s*", value):
        return _to_float(value.strip())
    return None


def compare(value: float, op: str, threshold: float) -> bool:
    if op == "gt":
        return value > threshold
    if op == "ge":
        return value >= threshold
    if op == "lt":
        return value < threshold
    if op == "le":
        return value <= threshold
    return False


def describe(op: str, threshold: float) -> str:
    return f"{VALUE_OP_SYMBOLS.get(op, op)} {format_number(threshold)}"


def format_number(value: float) -> str:
    return f"{value:g}"


def _followed_by_count(text: str, end: int) -> bool:
    return bool(re.match(_NOT_A_THRESHOLD, normalize_text(text[end:end + 20])))


def _to_float(value: str) -> float:
    return float(value.replace(",", "."))
