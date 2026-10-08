"""Chọn route cấp cao. Thứ tự rẻ → đắt: plan cache → low-cost mode → LLM router."""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.planning.plan_cache import get_plan_cache
from agent.planning.router import FALLBACK_DECISION, RouteDecision, decide_route
from agent.state import ROUTE_FHIR, STAGE_ROUTER, AgentState, get_deps
from api.schemas import ChatRequest
from app.config import get_settings
from llm.client import LlmCallError
from llm.usage import zero_usage


log = logging.getLogger(__name__)


async def route(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = get_deps(config)
    request: ChatRequest = state["request"]

    # 1) Plan cache: hit ở đây bỏ được CẢ router lẫn planner.
    cached = await get_plan_cache().lookup(
        question=request.message,
        user_role=request.user_role,
        conversation_context=state.get("conversation_context"),
    )
    if cached is not None:
        decision, plan = cached
        return {
            **_route_update(decision),
            "plan_steps": [step.as_dict() for step in plan.steps],
            "plan_answer_mode": plan.answer_mode,
            "plan_source": plan.source,
        }

    # 2) Low-cost mode: quota gần cạn thì không tiêu LLM cho việc định tuyến.
    if state.get("low_cost_mode"):
        return _route_update(
            RouteDecision(route=ROUTE_FHIR, reason="Low-cost mode: bỏ router LLM.", source="low_cost")
        )

    # 3) LLM router (có LRU cache riêng). Lỗi ⇒ route fhir: an toàn nhất vì validator vẫn
    # chặn quyền phía sau, còn general_chat/unsupported sẽ nuốt mất câu hỏi thật.
    usage = zero_usage()
    try:
        decision, usage = await decide_route(
            message=request.message,
            user_role=request.user_role,
            provided_patient_id=state.get("provided_patient_id"),
            conversation_context=state.get("conversation_context"),
            model=get_settings().route_model,
        )
    except LlmCallError as exc:
        log.info("Router LLM lỗi, dùng route mặc định: %s", exc)
        decision = FALLBACK_DECISION
    deps.record(STAGE_ROUTER, usage)
    return _route_update(decision)


def decision_from_state(state: AgentState) -> RouteDecision:
    return RouteDecision(
        route=state.get("route") or ROUTE_FHIR,
        meta_kind=state.get("route_meta_kind"),
        resource_hint=state.get("route_resource_hint"),
        safety_flag=state.get("route_safety_flag") or "none",
        reason=state.get("route_reason"),
        source=state.get("route_source") or "llm",
    )


def _route_update(decision: RouteDecision) -> dict[str, Any]:
    return {
        "route": decision.route,
        "route_meta_kind": decision.meta_kind,
        "route_resource_hint": decision.resource_hint,
        "route_safety_flag": decision.safety_flag,
        "route_reason": decision.reason,
        "route_source": decision.source,
    }
