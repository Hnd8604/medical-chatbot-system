"""Ước tính chi phí LLM cho response của chatbot-service.

Bảng giá này **phản chiếu** `model_pricing` của Spring (V1 seed) — Spring vẫn là
nguồn sự thật và tự tính lại cost khi lưu `usage_logs` (xem
`CostEstimationService`). Ở đây ta tính cùng công thức
(``token × giá / 1_000_000``, theo model của answer, trên **tổng** token đã gộp)
để field ``usage.estimated_cost_usd`` trong response/log **khớp** giá trị Spring lưu.

Nếu Spring cập nhật giá qua admin, đồng bộ lại bảng dưới đây. Model không có trong
bảng → trả 0.0 (Spring vẫn tính đúng nếu model có trong DB).

Đơn vị: USD trên 1 triệu token, dạng (input, output).
"""

from __future__ import annotations

from typing import Any


# (provider, model) đã normalize (lowercase) -> (giá input, giá output) / 1M token.
# Tên model trùng khớp cột `model` trong bảng model_pricing của Spring để cost nhất quán.
_PRICING_PER_1M: dict[tuple[str, str], tuple[float, float]] = {
    ("openai", "gpt-4.1-mini"): (0.40, 1.60),
    ("openai", "gpt-4o-mini"): (0.15, 0.60),
    ("groq", "llama-3.3-70b-versatile"): (0.59, 0.79),
    ("groq", "llama-3.1-8b-instant"): (0.05, 0.08),
}

# Alias model (tên trong LiteLLM config) -> (provider, model) đúng bảng model_pricing của
# Spring. Hỗ trợ cả alias lẫn tên model gốc mà gateway có thể trả về trong response.model.
_MODEL_ALIAS_MAP: dict[str, tuple[str, str]] = {
    "gpt-4o-mini": ("openai", "gpt-4o-mini"),
    "gpt-4.1-mini": ("openai", "gpt-4.1-mini"),
    "groq-llama-8b": ("groq", "llama-3.1-8b-instant"),
    "groq-llama-70b": ("groq", "llama-3.3-70b-versatile"),
    "llama-3.1-8b-instant": ("groq", "llama-3.1-8b-instant"),
    "llama-3.3-70b-versatile": ("groq", "llama-3.3-70b-versatile"),
}

_TOKENS_PER_MILLION = 1_000_000
_COST_SCALE = 6  # khớp COST_SCALE của Spring CostEstimationService


def provider_and_pricing_model(model: str) -> tuple[str, str]:
    key = (model or "").split("/")[-1].strip()
    if key in _MODEL_ALIAS_MAP:
        return _MODEL_ALIAS_MAP[key]
    provider = "groq" if "llama" in key.lower() or "groq" in key.lower() else "openai"
    return provider, key


def estimate_cost_usd(
    provider: str | None,
    model: str | None,
    input_tokens: int,
    output_tokens: int,
) -> float:
    """Ước tính chi phí USD cho một lượt gọi, cùng công thức với Spring.

    Trả 0.0 khi thiếu provider/model hoặc model không có trong bảng giá.
    """
    if not provider or not model:
        return 0.0
    price = _PRICING_PER_1M.get((provider.strip().lower(), model.strip().lower()))
    if price is None:
        return 0.0
    input_price, output_price = price
    cost = (
        max(0, input_tokens) * input_price
        + max(0, output_tokens) * output_price
    ) / _TOKENS_PER_MILLION
    return round(cost, _COST_SCALE)


def apply_estimated_cost(payload: dict[str, Any]) -> None:
    """Ghi cost ước tính vào ``payload['usage']`` theo model/provider của lượt này."""
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return
    usage["estimated_cost_usd"] = estimate_cost_usd(
        payload.get("llm_provider"),
        payload.get("llm_model"),
        int(usage.get("input_tokens") or 0),
        int(usage.get("output_tokens") or 0),
    )
