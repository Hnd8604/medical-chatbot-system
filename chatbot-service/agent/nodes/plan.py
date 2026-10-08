"""Lập kế hoạch FHIR: plan cache (đã cấp ở node route) → LLM planner → rule planner."""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.planning.plan import PLAN_SOURCE_CACHE, Plan
from agent.planning.planner import create_plan
from agent.planning.rule_planner import rule_plan
from agent.state import STAGE_PLANNER, AgentState, get_deps
from api.schemas import ChatRequest
from app.config import get_settings
from llm.client import LlmCallError


log = logging.getLogger(__name__)


async def plan(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    if state.get("plan_source") == PLAN_SOURCE_CACHE and state.get("plan_steps"):
        return {}

    request: ChatRequest = state["request"]
    if state.get("low_cost_mode"):
        return _rule_fallback(state, reason="Low-cost mode: lập kế hoạch bằng luật.")

    try:
        llm_plan, usage = await create_plan(
            message=request.message,
            user_role=request.user_role,
            provided_patient_id=state.get("provided_patient_id"),
            allowed_patient_ids=state.get("allowed_patient_ids") or [],
            conversation_context=state.get("conversation_context"),
            resource_hint=state.get("route_resource_hint"),
            model=get_settings().planner_model,
        )
    except LlmCallError as exc:
        log.info("Planner LLM lỗi, dùng rule planner: %s", exc)
        return _rule_fallback(state, reason=f"Planner LLM không khả dụng: {exc.detail}")

    get_deps(config).record(STAGE_PLANNER, usage)
    return _plan_update(llm_plan)


def has_plan(state: AgentState) -> bool:
    return bool(state.get("plan_steps"))


def _rule_fallback(state: AgentState, *, reason: str) -> dict[str, Any]:
    fallback = rule_plan(
        state["request"].message,
        provided_patient_id=state.get("provided_patient_id"),
        reason=reason,
    )
    if fallback is None:
        # Không nhận ra câu hỏi dữ liệu nào: graph sẽ hỏi lại thay vì đoán một truy vấn.
        return {"plan_steps": [], "plan_reason": reason}
    return _plan_update(fallback)


def _plan_update(value: Plan) -> dict[str, Any]:
    return {
        "plan_steps": [step.as_dict() for step in value.steps],
        "plan_answer_mode": value.answer_mode,
        "plan_source": value.source,
        "plan_reason": value.reason,
    }
