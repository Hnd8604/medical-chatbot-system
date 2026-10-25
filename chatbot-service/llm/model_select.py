"""Chọn model cho bước sinh câu trả lời — tất định, không tốn LLM call.

Planner đã cho biết lượt này phức tạp tới đâu (số step, có cần giải thích không, có đọc
dữ liệu nhiều bệnh nhân không), nên không cần một LLM Router riêng để phân loại lại:

- nhiều step / ``explain`` / dữ liệu nhiều bệnh nhân ⇒ ``model_complex``;
- còn lại ⇒ ``model_simple``;
- quota đã dùng >= ``settings.quota_downgrade_ratio`` ⇒ luôn ``model_simple`` (cost-aware).
"""

from __future__ import annotations

from dataclasses import dataclass

from app.config import get_settings


COMPLEXITY_SIMPLE = "simple"
COMPLEXITY_COMPLEX = "complex"

SOURCE_PLAN = "plan"
SOURCE_QUOTA_DOWNGRADE = "quota_downgrade"


@dataclass(frozen=True)
class ModelChoice:
    model: str
    complexity: str
    source: str


def select_answer_model(
    *,
    step_count: int,
    explain: bool,
    all_patients: bool,
    quota_used_ratio: float,
) -> ModelChoice:
    settings = get_settings()
    complex_turn = step_count > 1 or explain or all_patients
    complexity = COMPLEXITY_COMPLEX if complex_turn else COMPLEXITY_SIMPLE

    if complex_turn and quota_used_ratio >= settings.quota_downgrade_ratio:
        return ModelChoice(settings.model_simple, complexity, SOURCE_QUOTA_DOWNGRADE)
    model = settings.model_complex if complex_turn else settings.model_simple
    return ModelChoice(model, complexity, SOURCE_PLAN)
