"""Kiểu dữ liệu của một kế hoạch truy xuất FHIR nhiều bước.

Step sau tham chiếu kết quả step trước qua ``"$<step_id>.patient_id"`` — nhờ đó một
lượt trả lời được câu cần nhiều loại dữ liệu ("thuốc và chẩn đoán của bệnh nhân A").
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from agent.state import ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN
from llm.client import LlmCallError, clean_text


SUPPORTED_ANSWER_MODES = frozenset({ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN})
STEP_REF_PREFIX = "$"

PLAN_SOURCE_LLM = "llm"
PLAN_SOURCE_CACHE = "plan_cache"
PLAN_SOURCE_RULES = "rule_fallback"


@dataclass(frozen=True)
class PlanStep:
    id: str
    tool: str
    args: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {"id": self.id, "tool": self.tool, "args": dict(self.args)}

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "PlanStep":
        return cls(
            id=str(value.get("id") or "step"),
            tool=str(value.get("tool")),
            args=dict(value.get("args") or {}),
        )


@dataclass(frozen=True)
class Plan:
    steps: tuple[PlanStep, ...]
    answer_mode: str = ANSWER_MODE_DATA_ONLY
    reason: str | None = None
    source: str = PLAN_SOURCE_LLM

    @property
    def explain(self) -> bool:
        return self.answer_mode == ANSWER_MODE_EXPLAIN


def plan_from_dict(value: dict[str, Any], *, stage: str, source: str = PLAN_SOURCE_LLM) -> Plan:
    """Đọc plan từ JSON của planner LLM. Bỏ step hỏng; không còn step nào thì raise."""
    raw_steps = value.get("steps")
    if not isinstance(raw_steps, list):
        raise LlmCallError(stage, "Trường 'steps' phải là list.")

    steps: list[PlanStep] = []
    seen_ids: set[str] = set()
    for index, raw in enumerate(raw_steps, start=1):
        if not isinstance(raw, dict):
            continue
        tool = clean_text(raw.get("tool")) or clean_text(raw.get("name"))
        if not tool:
            continue
        step_id = _safe_step_id(clean_text(raw.get("id")), index, seen_ids)
        args = raw.get("args")
        steps.append(PlanStep(id=step_id, tool=tool, args=dict(args) if isinstance(args, dict) else {}))

    if not steps:
        raise LlmCallError(stage, "Plan không có step nào chạy được.")

    answer_mode = clean_text(value.get("answer_mode")) or ANSWER_MODE_DATA_ONLY
    if answer_mode not in SUPPORTED_ANSWER_MODES:
        answer_mode = ANSWER_MODE_DATA_ONLY

    return Plan(
        steps=tuple(steps),
        answer_mode=answer_mode,
        reason=clean_text(value.get("reason")),
        source=source,
    )


def _safe_step_id(value: str | None, index: int, seen: set[str]) -> str:
    base = "".join(ch if ch.isalnum() or ch in "_-" else "_" for ch in (value or "")).strip("_")
    step_id = base or f"step_{index}"
    while step_id in seen:
        step_id = f"{step_id}_{index}"
    seen.add(step_id)
    return step_id
