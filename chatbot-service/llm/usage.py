"""Kế toán token/cost theo shape chung ``{input_tokens, output_tokens, cost_usd}``.

``cost_usd`` là chi phí **thật** do LiteLLM gateway báo (header
``x-litellm-response-cost``), không phải giá ước tính. ``None`` nghĩa là *không biết*
(gateway không trả header) — khác hẳn ``0.0`` (biết chắc không tốn gì, vd không có call
nào). Gộp nhiều usage: chỉ cần một phần không biết là tổng cũng không biết; Spring ghi
call không có cost là 0 kèm nguồn ``unavailable`` chứ không tự ước tính.
"""

from __future__ import annotations

from typing import Any


def zero_usage() -> dict[str, Any]:
    return {"input_tokens": 0, "output_tokens": 0, "cost_usd": 0.0}


def combine_usage(*usages: dict[str, Any] | None) -> dict[str, Any]:
    combined = zero_usage()
    for usage in usages:
        if not usage:
            continue
        combined["input_tokens"] += _number(usage.get("input_tokens"))
        combined["output_tokens"] += _number(usage.get("output_tokens"))
        cost = _cost(usage.get("cost_usd"))
        if cost is None or combined["cost_usd"] is None:
            combined["cost_usd"] = None
        else:
            combined["cost_usd"] = round(combined["cost_usd"] + cost, 8)
    return combined


def usage_from_response(response: Any, cost_usd: float | None) -> dict[str, Any]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return {"input_tokens": 0, "output_tokens": 0, "cost_usd": cost_usd}
    return {
        "input_tokens": _number(getattr(usage, "prompt_tokens", 0)),
        "output_tokens": _number(getattr(usage, "completion_tokens", 0)),
        "cost_usd": cost_usd,
    }


def parse_cost(value: Any) -> float | None:
    """Đọc cost USD từ header gateway; giá trị thiếu/sai/âm ⇒ ``None`` (không biết)."""
    if value is None:
        return None
    try:
        cost = float(value)
    except (TypeError, ValueError):
        return None
    if cost != cost or cost < 0:  # NaN hoặc âm
        return None
    return cost


def _cost(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def _number(value: Any) -> int | float:
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else 0
