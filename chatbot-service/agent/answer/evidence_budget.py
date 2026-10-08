"""Cắt evidence xuống dưới một trần token trước khi vào prompt answer.

Input token của answer là khoản chi lớn nhất mỗi lượt, và plan nhiều bước làm nó phình
theo cấp số nhân (3 step × 10 bản ghi × full ``data``). Bước này thuần tất định:
chỉ **bỏ bớt**, không viết thêm gì — nên không có rủi ro bịa dữ liệu.

Thứ tự ưu tiên khi phải cắt:
1. Giữ bản ghi mới nhất (theo mốc thời gian trong ``data``).
2. Trước khi bỏ hẳn bản ghi, bỏ các trường phụ trợ ít khi cần cho câu trả lời.
"""

from __future__ import annotations

from typing import Any


# Ước lượng token: tiếng Việt + JSON rơi vào khoảng 4 ký tự/token.
CHARS_PER_TOKEN = 4

# Trường bị bỏ ở vòng cắt đầu tiên (không ảnh hưởng giá trị/ngày/tên).
_OPTIONAL_FIELDS = (
    "note",
    "reference_range",
    "interpretation",
    "category",
    "identifier",
    "encounter",
    "raw",
)

# Khoá chứa mốc thời gian trong evidence.data, xếp theo mức ưu tiên.
_TIME_KEYS = (
    "effective_time",
    "recorded_date",
    "onset_date",
    "authored_on",
    "start",
    "date",
)


def apply_evidence_budget(
    evidence: list[dict[str, Any]],
    *,
    max_chars: int,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Trả (evidence đã cắt, thống kê).

    Thống kê gồm ``chars_before``/``chars_after``/``dropped_items``/``pruned_fields``
    để dashboard đo được "giảm bao nhiêu token nhờ pruning".
    """
    if not isinstance(evidence, list) or not evidence:
        return [], {"chars_before": 0, "chars_after": 0, "dropped_items": 0, "pruned_fields": 0}

    chars_before = _size(evidence)
    if max_chars <= 0 or chars_before <= max_chars:
        return evidence, {
            "chars_before": chars_before,
            "chars_after": chars_before,
            "dropped_items": 0,
            "pruned_fields": 0,
        }

    ordered = sorted(evidence, key=_recency_key, reverse=True)

    # Vòng 1: bỏ trường phụ trợ.
    pruned_fields = 0
    trimmed: list[dict[str, Any]] = []
    for item in ordered:
        item, removed = _strip_optional_fields(item)
        pruned_fields += removed
        trimmed.append(item)

    # Vòng 2: bỏ dần bản ghi cũ nhất cho tới khi lọt trần (luôn giữ lại ít nhất 1).
    dropped = 0
    while len(trimmed) > 1 and _size(trimmed) > max_chars:
        trimmed.pop()
        dropped += 1

    return trimmed, {
        "chars_before": chars_before,
        "chars_after": _size(trimmed),
        "dropped_items": dropped,
        "pruned_fields": pruned_fields,
    }


def estimate_tokens(chars: int) -> int:
    return max(0, chars // CHARS_PER_TOKEN)


def _size(evidence: list[dict[str, Any]]) -> int:
    # Đo bằng repr thay vì json.dumps: rẻ hơn và chỉ cần độ lớn tương đối.
    return sum(len(repr(item)) for item in evidence)


def _strip_optional_fields(item: dict[str, Any]) -> tuple[dict[str, Any], int]:
    data = item.get("data")
    if not isinstance(data, dict):
        return item, 0
    removed = 0
    slim = dict(data)
    for field in _OPTIONAL_FIELDS:
        if field in slim:
            slim.pop(field)
            removed += 1
    if not removed:
        return item, 0
    return {**item, "data": slim}, removed


def _recency_key(item: Any) -> str:
    """Mốc thời gian dạng chuỗi ISO; thiếu thì về chuỗi rỗng (bị coi là cũ nhất)."""
    if not isinstance(item, dict):
        return ""
    data = item.get("data")
    if not isinstance(data, dict):
        return ""
    for key in _TIME_KEYS:
        value = data.get(key)
        if isinstance(value, str) and value:
            return value
        if isinstance(value, dict):
            nested = value.get("start") or value.get("date")
            if isinstance(nested, str) and nested:
                return nested
    period = data.get("period")
    if isinstance(period, dict):
        start = period.get("start")
        if isinstance(start, str):
            return start
    return ""
