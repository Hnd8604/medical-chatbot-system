"""Chạy plan đã validate; lượt chạy được thì ghi plan vào plan cache."""

from __future__ import annotations

from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.execution.executor import execute_plan
from agent.nodes.route import decision_from_state
from agent.nodes.validate import plan_from_state
from agent.planning.plan import PLAN_SOURCE_LLM
from agent.planning.plan_cache import get_plan_cache
from agent.state import RESPONSE_STATUS_ANSWERED, RESPONSE_STATUS_NO_DATA, AgentState, get_deps
from api.schemas import ChatRequest


async def execute(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = get_deps(config)
    request: ChatRequest = state["request"]
    plan = plan_from_state(state)

    result = await execute_plan(
        plan,
        client=deps.client,
        user_role=request.user_role,
        allowed_patient_ids=state.get("allowed_patient_ids") or [],
        provided_patient_id=state.get("provided_patient_id"),
    )

    # Chỉ cache plan của LLM khi lượt này thật sự chạy được — không nhân bản plan hỏng.
    if plan.source == PLAN_SOURCE_LLM and result.response_status in {RESPONSE_STATUS_ANSWERED, RESPONSE_STATUS_NO_DATA}:
        await get_plan_cache().save(
            question=request.message,
            user_role=request.user_role,
            conversation_context=state.get("conversation_context"),
            decision=decision_from_state(state),
            plan=plan,
        )

    return {
        "payload": {**result.payload, "tool_name": result.tool_name},
        "response_status": result.response_status,
        "resolved_patient_id": result.resolved_patient_id,
        "executed_steps": result.executed_steps,
        "failed_steps": result.failed_steps,
    }
