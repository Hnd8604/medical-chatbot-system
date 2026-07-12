"""Ước tính chi phí LLM cho response của chatbot-service.

Bảng giá này **phản chiếu** `model_pricing` của Spring (V1 seed) — Spring vẫn là
nguồn sự thật và tự tính lại cost khi lưu `usage_logs` (xem
`CostEstimationService`). Ở đây ta tính cùng công thức
(``token × giá / 1_000_000``, theo model của answer, trên **tổng** token đã gộp)
để field ``usage.estimated_cost_usd`` trong response/log **khớp** giá trị Spring
lưu, thay vì luôn = 0.

Nếu Spring cập nhật giá qua admin, đồng bộ lại bảng dưới đây. Model không có trong
bảng → trả 0.0 (giữ hành vi cũ; Spring vẫn tính đúng nếu model có trong DB).

Đơn vị: USD trên 1 triệu token, dạng (input, output).
"""

from __future__ import annotations

# (provider, model) đã normalize (lowercase) -> (giá input, giá output) / 1M token.
# Tên model trùng khớp cột `model` trong bảng model_pricing của Spring để cost nhất quán.
_PRICING_PER_1M: dict[tuple[str, str], tuple[float, float]] = {
    ("openai", "gpt-4.1-mini"): (0.40, 1.60),
    ("openai", "gpt-4o-mini"): (0.15, 0.60),
    ("groq", "llama-3.3-70b-versatile"): (0.59, 0.79),
    ("groq", "llama-3.1-8b-instant"): (0.05, 0.08),
}

_TOKENS_PER_MILLION = 1_000_000
_COST_SCALE = 6  # khớp COST_SCALE của Spring CostEstimationService


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
