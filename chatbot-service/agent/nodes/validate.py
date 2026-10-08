"""Áp chính sách lên plan (thuần, không I/O).

- Vi phạm chính sách role ⇒ ``PolicyError`` (403), nổi thẳng lên route.
- Plan hỏng (tool lạ, quá nhiều step...) ⇒ ghi ``plan_error``; graph rẽ sang ``clarify``.
"""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.errors import PlanError
from agent.planning.plan import PLAN_SOURCE_LLM, Plan, PlanStep
from agent.planning.validator import validate_plan
from agent.state import ANSWER_MODE_DATA_ONLY, AgentState
from api.schemas import ChatRequest


log = logging.getLogger(__name__)


async def validate(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    request: ChatRequest = state["request"]
    try:
        validated = validate_plan(
            plan_from_state(state),
            message=request.message,
            user_role=request.user_role,
            allowed_patient_ids=state.get("allowed_patient_ids") or [],
            provided_patient_id=state.get("provided_patient_id"),
        )
    except PlanError as exc:
        log.info("Plan không hợp lệ, hỏi lại người dùng: %s", exc.detail)
        return {"plan_error": exc.detail}
    return {
        "plan_steps": [step.as_dict() for step in validated.steps],
        "plan_answer_mode": validated.answer_mode,
    }


def plan_from_state(state: AgentState) -> Plan:
    steps = tuple(
        PlanStep.from_dict(item) for item in (state.get("plan_steps") or []) if item.get("tool")
    )
    if not steps:
        raise PlanError("Không lập được kế hoạch FHIR hợp lệ cho câu hỏi này.")
    return Plan(
        steps=steps,
        answer_mode=state.get("plan_answer_mode") or ANSWER_MODE_DATA_ONLY,
        reason=state.get("plan_reason"),
        source=state.get("plan_source") or PLAN_SOURCE_LLM,
    )
