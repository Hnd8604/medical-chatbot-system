"""LLM #2 — lập kế hoạch truy xuất FHIR nhiều bước.

Điểm khác quan trọng nhất so với pipeline ``/chat`` hiện tại: một lượt có thể sinh
NHIỀU step, và step sau tham chiếu kết quả step trước qua ``"$resolve_patient.patient_id"``.
Nhờ đó trả lời được câu kiểu "bệnh nhân Nguyễn Văn A đang dùng thuốc gì và có chẩn đoán gì"
mà pipeline một-tool không làm được.

Planner chỉ ĐỀ XUẤT. Mọi ràng buộc (allowlist, limit, quyền theo role) do
``plan_validator`` quyết định.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from agents.intent.constants import (
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
)
from agents.intent.models import IntentPlan
from app.config import get_settings
from fhir.tool_registry import tool_catalog_for_prompt
from langgraph_agent.errors import AgentLlmError
from langgraph_agent.llm import (
    STAGE_PLANNER,
    call_llm,
    clean_text,
    parse_json_object,
)
from langgraph_agent.prompts import PLANNER_SYSTEM_PROMPT_TEMPLATE
from langgraph_agent.state import ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN


SUPPORTED_ANSWER_MODES = frozenset({ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN})

# Tool mà rule fallback biết dựng plan 1 bước (xem from_intent_plan).
_RULE_FALLBACK_TOOLS = frozenset(
    {
        TOOL_GET_PATIENT,
        TOOL_GET_OBSERVATIONS,
        TOOL_GET_ENCOUNTERS,
        TOOL_GET_CONDITIONS,
        TOOL_GET_MEDICATIONS,
        TOOL_SEARCH_PATIENTS,
        TOOL_GET_RESOURCE,
    }
)


@dataclass(frozen=True)
class PlanStep:
    id: str
    tool: str
    args: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {"id": self.id, "tool": self.tool, "args": dict(self.args)}


@dataclass(frozen=True)
class Plan:
    steps: tuple[PlanStep, ...]
    answer_mode: str = ANSWER_MODE_DATA_ONLY
    reason: str | None = None
    source: str = "llm"

    @property
    def explain(self) -> bool:
        return self.answer_mode == ANSWER_MODE_EXPLAIN

    def as_dict(self) -> dict[str, Any]:
        return {
            "steps": [step.as_dict() for step in self.steps],
            "answer_mode": self.answer_mode,
            "reason": self.reason,
            "source": self.source,
        }


def plan_from_dict(value: dict[str, Any], *, source: str = "llm") -> Plan:
    raw_steps = value.get("steps")
    if not isinstance(raw_steps, list):
        raise AgentLlmError(STAGE_PLANNER, "Trường 'steps' phải là list.")

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
        raise AgentLlmError(STAGE_PLANNER, "Plan không có step nào chạy được.")

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


async def create_plan(
    *,
    message: str,
    user_role: str,
    provided_patient_id: str | None,
    allowed_patient_ids: list[str],
    conversation_context: dict[str, Any] | None,
    resource_hint: str | None,
    model: str,
) -> tuple[Plan, dict[str, int | float]]:
    settings = get_settings()
    system_prompt = PLANNER_SYSTEM_PROMPT_TEMPLATE.format(
        tool_catalog=tool_catalog_for_prompt(),
        max_steps=settings.agent_max_plan_steps,
    )
    payload = {
        "message": message,
        "user_role": user_role,
        "provided_patient_id": provided_patient_id,
        "allowed_patient_ids": allowed_patient_ids,
        "resource_hint": resource_hint,
        "context": conversation_context or {},
    }
    result = await call_llm(
        stage=STAGE_PLANNER,
        model=model,
        system_prompt=system_prompt,
        user_payload=payload,
        max_output_tokens=500,
    )
    return plan_from_dict(parse_json_object(result.content, stage=STAGE_PLANNER)), result.usage


def from_intent_plan(plan: IntentPlan) -> Plan:
    """Đường lui khi planner LLM lỗi: dựng plan 1 bước từ rule-based extractor.

    Đây là lý do pipeline cũ vẫn đáng giữ — nó trở thành fallback của pipeline mới
    thay vì phải viết một bộ retry riêng (xem docs §6.4).
    """
    tool = plan.tool_name if plan.tool_name in _RULE_FALLBACK_TOOLS else TOOL_SEARCH_PATIENTS
    args: dict[str, Any] = {}
    if tool == TOOL_SEARCH_PATIENTS:
        args = {
            "name": plan.search_name,
            "phone": plan.search_phone,
            "birth_date": plan.search_birth_date,
            "identifier": plan.search_identifier,
            "limit": plan.limit,
        }
    elif tool == TOOL_GET_RESOURCE:
        args = {"resource_type": plan.resource_type, "resource_id": plan.resource_id}
    else:
        args = {"patient_id": plan.patient_id, "limit": plan.limit}
        if tool == TOOL_GET_OBSERVATIONS and plan.observation_type:
            args["observation_type"] = plan.observation_type

    return Plan(
        steps=(PlanStep(id="rule_step", tool=tool, args={k: v for k, v in args.items() if v is not None}),),
        answer_mode=ANSWER_MODE_EXPLAIN if plan.explain else ANSWER_MODE_DATA_ONLY,
        reason="Planner LLM không khả dụng; dùng rule-based intent extractor.",
        source="rule_fallback",
    )
