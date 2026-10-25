"""Nhận diện mốc thời gian trong câu hỏi tiếng Việt → khoảng ngày (date_from, date_to).

Thuần luật, tất định: validator dùng nó để điền/ghi đè ngày cho tool, plan cache dùng nó
để điền lại khoảng ngày khi tái dùng plan. Ngày trả về là ``YYYY-MM-DD``, cả hai đầu
đều tính (FHIR ``ge``/``le``). Một đầu có thể là ``None`` (vd "từ ngày 01/03/2026").

Ví dụ (hôm nay 2026-10-23):

- "huyết áp 3 tháng qua"        → 2026-07-23 .. 2026-10-23
- "tháng trước"                  → 2026-09-01 .. 2026-09-30
- "tháng 3" / "tháng 3/2025"     → 2026-03-01 .. 2026-03-31 / 2025-03-01 .. 2025-03-31
- "năm 2025", "năm ngoái"        → 2025-01-01 .. 2025-12-31
- "từ 01/02/2026 đến 15/03/2026" → 2026-02-01 .. 2026-03-15
"""

from __future__ import annotations

import calendar
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from nlp.text import normalize_text


DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh"

_NUMBER_WORDS = {
    "mot": 1, "hai": 2, "ba": 3, "bon": 4, "tu": 4, "nam": 5, "sau": 6, "bay": 7,
    "tam": 8, "chin": 9, "muoi": 10, "muoi mot": 11, "muoi hai": 12, "nua": 0,
}
_NUMBER = r"(\d{1,3}|muoi hai|muoi mot|muoi|mot|hai|ba|bon|tu|nam|sau|bay|tam|chin)"
_UNIT = r"(ngay|tuan|thang|nam)"
_DATE = r"(\d{1,2})[/-](\d{1,2})[/-]((?:19|20)\d{2})|((?:19|20)\d{2})-(\d{2})-(\d{2})"
# Ngày đứng sau "sinh" là ngày sinh (tiêu chí tìm bệnh nhân), không phải mốc thời gian.
_BIRTH_PREFIX = re.compile(r"sinh\s*(ngay|nam)?\s*$")


@dataclass(frozen=True)
class TimeRange:
    date_from: str | None
    date_to: str | None

    def as_args(self) -> dict[str, str]:
        args: dict[str, str] = {}
        if self.date_from:
            args["date_from"] = self.date_from
        if self.date_to:
            args["date_to"] = self.date_to
        return args

    def label(self) -> str:
        if self.date_from and self.date_to:
            if self.date_from == self.date_to:
                return f"ngày {self.date_from}"
            return f"từ {self.date_from} đến {self.date_to}"
        if self.date_from:
            return f"từ {self.date_from}"
        if self.date_to:
            return f"đến {self.date_to}"
        return ""


def today_local(timezone: str = DEFAULT_TIMEZONE) -> date:
    return datetime.now(ZoneInfo(timezone)).date()


def normalize_iso_date(value: Any) -> str | None:
    """Chuẩn hoá ngày planner sinh ra; chỉ nhận ngày hợp lệ dạng YYYY-MM-DD."""
    if not isinstance(value, str):
        return None
    match = re.fullmatch(r"\s*((?:19|20)\d{2})-(\d{1,2})-(\d{1,2})(?:T.*)?\s*", value)
    if not match:
        return None
    parsed = _safe_date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
    return parsed.isoformat() if parsed else None


def range_label(date_from: Any, date_to: Any) -> str:
    return TimeRange(normalize_iso_date(date_from), normalize_iso_date(date_to)).label()


def extract_time_range(message: str, *, today: date | None = None) -> TimeRange | None:
    today = today or today_local()
    text = normalize_text(message or "")
    for parser in (
        _explicit_range,
        _month_range,
        _bounded_date,
        _relative_window,
        _named_period,
        _quarter,
        _specific_month,
        _specific_year,
        _single_date,
    ):
        result = parser(text, today)
        if result is not None:
            return result
    return None


# --------------------------------------------------------------------------- parsers


def _explicit_range(text: str, today: date) -> TimeRange | None:
    pattern = rf"(?:tu\s+(?:ngay\s+)?)?(?:{_DATE})\s*(?:den|toi|\-|–)\s*(?:ngay\s+)?(?:{_DATE})"
    for match in re.finditer(pattern, text):
        if _is_birth_context(text, match.start()):
            continue
        groups = match.groups()
        start = _date_from_groups(groups[:6])
        end = _date_from_groups(groups[6:])
        if start and end:
            start, end = sorted((start, end))
            return TimeRange(start.isoformat(), end.isoformat())
    return None


def _month_range(text: str, today: date) -> TimeRange | None:
    match = re.search(
        r"tu thang (\d{1,2})(?:\s*(?:/|-|nam)\s*((?:19|20)\d{2}))?\s*(?:den|toi)\s*"
        r"(?:het\s+)?thang (\d{1,2})(?:\s*(?:/|-|nam)\s*((?:19|20)\d{2}))?",
        text,
    )
    if not match:
        return None
    start_month, end_month = int(match.group(1)), int(match.group(3))
    if not (1 <= start_month <= 12 and 1 <= end_month <= 12):
        return None
    year = re.search(r"nam ((?:19|20)\d{2})", text[match.end():match.end() + 12])
    end_year = int(match.group(4) or (year.group(1) if year else 0) or _year_for_month(end_month, today))
    start_year = int(match.group(2) or (end_year if start_month <= end_month else end_year - 1))
    return TimeRange(date(start_year, start_month, 1).isoformat(), _month_end(end_year, end_month).isoformat())


def _bounded_date(text: str, today: date) -> TimeRange | None:
    for match in re.finditer(rf"(truoc|sau|tu|den|toi)\s+(?:ngay\s+)?(?:{_DATE})", text):
        if _is_birth_context(text, match.start()):
            continue
        day = _date_from_groups(match.groups()[1:7])
        if day is None:
            continue
        keyword = match.group(1)
        if keyword == "truoc":
            return TimeRange(None, (day - timedelta(days=1)).isoformat())
        if keyword == "sau":
            return TimeRange((day + timedelta(days=1)).isoformat(), None)
        if keyword == "tu":
            return TimeRange(day.isoformat(), today.isoformat())
        return TimeRange(None, day.isoformat())
    return None


def _relative_window(text: str, today: date) -> TimeRange | None:
    patterns = (
        rf"(?:trong\s+(?:vong\s+)?)?{_NUMBER}\s+{_UNIT}\s+(?:qua|gan day|gan nhat|vua qua|tro lai day|tro lai|nay)\b",
        rf"trong\s+(?:vong\s+)?{_NUMBER}\s+{_UNIT}\s+(?:truoc|vua roi)\b",
        rf"trong\s+vong\s+{_NUMBER}\s+{_UNIT}\b",
        rf"\b{_NUMBER}\s+{_UNIT}\s+(?:do lai|tro lai)\b",
    )
    for pattern in patterns:
        match = re.search(pattern, text)
        if not match:
            continue
        amount = _number(match.group(1))
        if not amount:
            continue
        start = _shift(today, match.group(2), -amount)
        return TimeRange(start.isoformat(), today.isoformat())
    match = re.search(r"\b(?:trong\s+)?(tuan|thang|nam)\s+(?:qua|vua qua)\b", text)
    if match:
        return TimeRange(_shift(today, match.group(1), -1).isoformat(), today.isoformat())
    match = re.search(r"\b(nua thang|nua nam)\s+(?:qua|gan day|gan nhat|vua qua)\b", text)
    if match:
        days = 15 if match.group(1) == "nua thang" else 182
        return TimeRange((today - timedelta(days=days)).isoformat(), today.isoformat())
    return None


def _named_period(text: str, today: date) -> TimeRange | None:
    if re.search(r"\bhom nay\b", text):
        return TimeRange(today.isoformat(), today.isoformat())
    if re.search(r"\bhom qua\b", text):
        day = today - timedelta(days=1)
        return TimeRange(day.isoformat(), day.isoformat())
    monday = today - timedelta(days=today.weekday())
    if re.search(r"\btuan nay\b", text):
        return TimeRange(monday.isoformat(), today.isoformat())
    if re.search(r"\btuan (truoc|roi|vua roi)\b", text):
        return TimeRange((monday - timedelta(days=7)).isoformat(), (monday - timedelta(days=1)).isoformat())
    if re.search(r"\bthang nay\b", text):
        return TimeRange(today.replace(day=1).isoformat(), today.isoformat())
    if re.search(r"\bthang (truoc|roi|vua roi)\b", text):
        last_month_end = today.replace(day=1) - timedelta(days=1)
        return TimeRange(last_month_end.replace(day=1).isoformat(), last_month_end.isoformat())
    if re.search(r"\bnam nay\b", text):
        return TimeRange(date(today.year, 1, 1).isoformat(), today.isoformat())
    if re.search(r"\bnam (ngoai|truoc|roi|vua roi)\b", text):
        year = today.year - 1
        return TimeRange(date(year, 1, 1).isoformat(), date(year, 12, 31).isoformat())
    return None


def _quarter(text: str, today: date) -> TimeRange | None:
    match = re.search(r"\bquy ([1-4]|i{1,3}|iv)\b(?:\s*(?:/|-|nam)\s*((?:19|20)\d{2}))?", text)
    if not match:
        return None
    raw = match.group(1)
    quarter = {"i": 1, "ii": 2, "iii": 3, "iv": 4}.get(raw) or int(raw)
    first_month = 3 * (quarter - 1) + 1
    year = int(match.group(2)) if match.group(2) else _year_for_month(first_month, today)
    return TimeRange(date(year, first_month, 1).isoformat(), _month_end(year, first_month + 2).isoformat())


def _specific_month(text: str, today: date) -> TimeRange | None:
    for match in re.finditer(r"\bthang (\d{1,2})(?:\s*(?:/|-|nam)\s*((?:19|20)\d{2}))?\b", text):
        month = int(match.group(1))
        if not 1 <= month <= 12:
            continue
        year = int(match.group(2)) if match.group(2) else _year_for_month(month, today)
        return TimeRange(date(year, month, 1).isoformat(), _month_end(year, month).isoformat())
    return None


def _specific_year(text: str, today: date) -> TimeRange | None:
    for match in re.finditer(r"\b(?:nam|trong)\s+((?:19|20)\d{2})\b", text):
        if _is_birth_context(text, match.start()):
            continue
        year = int(match.group(1))
        return TimeRange(date(year, 1, 1).isoformat(), date(year, 12, 31).isoformat())
    return None


def _single_date(text: str, today: date) -> TimeRange | None:
    for match in re.finditer(rf"\bngay\s+(?:{_DATE})", text):
        if _is_birth_context(text, match.start()):
            continue
        day = _date_from_groups(match.groups()[:6])
        if day:
            return TimeRange(day.isoformat(), day.isoformat())
    return None


# --------------------------------------------------------------------------- helpers


def _is_birth_context(text: str, start: int) -> bool:
    return bool(_BIRTH_PREFIX.search(text[max(0, start - 16):start]))


def _date_from_groups(groups: tuple[str | None, ...]) -> date | None:
    day, month, year, iso_year, iso_month, iso_day = groups
    if year:
        return _safe_date(int(year), int(month), int(day))
    if iso_year:
        return _safe_date(int(iso_year), int(iso_month), int(iso_day))
    return None


def _safe_date(year: int, month: int, day: int) -> date | None:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _number(value: str) -> int:
    return int(value) if value.isdigit() else _NUMBER_WORDS.get(value, 0)


def _shift(today: date, unit: str, amount: int) -> date:
    if unit == "ngay":
        return today + timedelta(days=amount)
    if unit == "tuan":
        return today + timedelta(weeks=amount)
    if unit == "thang":
        return _add_months(today, amount)
    return _add_months(today, 12 * amount)


def _add_months(day: date, months: int) -> date:
    index = day.year * 12 + (day.month - 1) + months
    year, month = divmod(index, 12)
    month += 1
    return date(year, month, min(day.day, calendar.monthrange(year, month)[1]))


def _month_end(year: int, month: int) -> date:
    return date(year, month, calendar.monthrange(year, month)[1])


def _year_for_month(month: int, today: date) -> int:
    # "tháng 11" hỏi vào tháng 10 ⇒ tháng 11 năm trước (dữ liệu không ở tương lai).
    return today.year if month <= today.month else today.year - 1
