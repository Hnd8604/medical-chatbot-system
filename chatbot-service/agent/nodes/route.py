"""Chọn route cấp cao. Thứ tự rẻ → đắt: plan cache → low-cost mode (luật) → LLM router."""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.planning.plan_cache import get_plan_cache
from agent.planning.router import FALLBACK_DECISION, RouteDecision, decide_route
from agent.planning.rule_router import rule_route
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

    # 2) Low-cost mode: quota gần cạn thì định tuyến bằng luật — vẫn chặn câu xin tư vấn
    # y khoa và nhận ra chào hỏi / câu hỏi về cuộc trò chuyện.
    if state.get("low_cost_mode"):
        return _route_update(rule_route(request.message))

    # 3) LLM router (có LRU cache riêng). Lỗi ⇒ router luật, như low-cost mode: vẫn chặn câu
    # xin chẩn đoán/kê đơn và nhận ra chào hỏi; mọi câu luật không nhận ra đều về fhir
    # (validator vẫn chặn quyền phía sau, không nuốt mất câu hỏi dữ liệu thật).
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
        log.info("Router LLM lỗi, dùng router luật: %s", exc)
        decision = _rule_fallback(request.message)
    deps.record(STAGE_ROUTER, usage)
    return _route_update(decision)


def _rule_fallback(message: str) -> RouteDecision:
    decision = rule_route(message)
    return RouteDecision(**{**decision.as_dict(), "source": FALLBACK_DECISION.source})


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
