"""Kế toán token theo shape chung ``{input_tokens, output_tokens, estimated_cost_usd}``."""

from __future__ import annotations

from typing import Any


def zero_usage() -> dict[str, int | float]:
    return {"input_tokens": 0, "output_tokens": 0, "estimated_cost_usd": 0}


def combine_usage(*usages: dict[str, Any] | None) -> dict[str, int | float]:
    combined = zero_usage()
    for usage in usages:
        if not usage:
            continue
        combined["input_tokens"] += _number(usage.get("input_tokens"))
        combined["output_tokens"] += _number(usage.get("output_tokens"))
        combined["estimated_cost_usd"] += _number(usage.get("estimated_cost_usd"))
    return combined


def usage_from_response(response: Any) -> dict[str, int | float]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return zero_usage()
    return {
        "input_tokens": _number(getattr(usage, "prompt_tokens", 0)),
        "output_tokens": _number(getattr(usage, "completion_tokens", 0)),
        # Cost tính MỘT LẦN ở bước finalize (llm/pricing.py), không cộng dồn ở đây.
        "estimated_cost_usd": 0,
    }


def _number(value: Any) -> int | float:
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else 0
