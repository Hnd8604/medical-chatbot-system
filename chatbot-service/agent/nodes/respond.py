"""Sinh câu trả lời cho nhánh FHIR từ payload mà executor đã gộp.

Lượt ``partial`` (một số step lỗi): trả lời từ phần dữ liệu lấy được, nối câu lưu ý
tất định về phần còn thiếu, và không ghi semantic cache.
"""

from __future__ import annotations

from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.answer.finalize import CacheTarget, TurnMeta, finalize_answer
from agent.nodes.validate import plan_from_state
from agent.state import (
    RESPONSE_STATUS_PARTIAL,
    STAGE_ANSWER,
    STAGE_PLANNER,
    STAGE_ROUTER,
    STAGE_SUMMARY,
    AgentState,
    get_deps,
)
from api.schemas import ChatRequest
from llm.model_select import select_answer_model
from tools.registry import (
    ALL_PATIENT_TOOLS,
    TOOL_EXPLAIN_CONCEPT,
    TOOL_FHIR_STATUS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATION_TREND,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
    base_tool_name,
)


# Tên phần dữ liệu cho câu lưu ý partial, theo tool gốc.
_DATA_LABELS = {
    TOOL_FHIR_STATUS: "trạng thái máy chủ FHIR",
    TOOL_SEARCH_PATIENTS: "kết quả tìm bệnh nhân",
    TOOL_GET_PATIENT: "thông tin hành chính",
    TOOL_GET_RESOURCE: "tài nguyên FHIR được hỏi",
    TOOL_GET_OBSERVATIONS: "chỉ số/xét nghiệm",
    TOOL_GET_OBSERVATION_TREND: "xu hướng chỉ số",
    TOOL_GET_ENCOUNTERS: "lần khám",
    TOOL_GET_CONDITIONS: "chẩn đoán",
    TOOL_GET_MEDICATIONS: "đơn thuốc",
    TOOL_EXPLAIN_CONCEPT: "giải thích thuật ngữ",
}


async def respond(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = get_deps(config)
    request: ChatRequest = state["request"]
    plan = plan_from_state(state)
    payload = dict(state["payload"])
    failed_steps = state.get("failed_steps") or []
    partial = state.get("response_status") == RESPONSE_STATUS_PARTIAL

    model_choice = select_answer_model(
        step_count=len(plan.steps),
        explain=plan.explain,
        all_patients=any(step.tool in ALL_PATIENT_TOOLS for step in plan.steps),
        quota_used_ratio=request.quota_used_ratio,
    )
    meta = TurnMeta(
        tool_name=payload.pop("tool_name"),
        intent=payload.get("intent") or "unknown",
        patient_id=state.get("resolved_patient_id"),
        explain=plan.explain,
        all_patients=bool(payload.get("all_patients")),
        source=f"agent_{plan.source}",
        usage=deps.usage_of(STAGE_ROUTER, STAGE_PLANNER),
    )
    finalized = await finalize_answer(
        payload,
        question=request.message,
        meta=meta,
        answer_generator=deps.answer_generator,
        model_choice=model_choice,
        summary_task=deps.summary_task,
        conversation_context=state.get("conversation_context"),
        answer_mode=plan.answer_mode,
        step_count=len(plan.steps),
        # Câu trả lời thiếu dữ liệu do sự cố tạm thời không được phục vụ lại từ cache.
        cache=None if partial else CacheTarget(service=deps.cache_service, user_id=request.user_id),
        low_cost=bool(state.get("low_cost_mode")),
    )
    if partial:
        finalized["answer"] = f"{finalized['answer'].rstrip()}\n\n{partial_note(failed_steps)}"
    deps.record(STAGE_ANSWER, finalized.get("answer_usage"))
    deps.record(STAGE_SUMMARY, finalized.get("summary_usage"))
    finalized["response_status"] = state.get("response_status")
    # Chỉ trả tên tool đã chạy — args có thể chứa định danh bệnh nhân.
    finalized["plan_steps"] = [
        {"id": step["id"], "tool": step["tool"]} for step in state.get("executed_steps") or []
    ]
    if failed_steps:
        finalized["failed_steps"] = failed_steps
    return {"payload": finalized}


def partial_note(failed_steps: list[dict[str, Any]]) -> str:
    labels: list[str] = []
    for step in failed_steps:
        label = _DATA_LABELS.get(base_tool_name(step.get("tool") or ""), "một phần dữ liệu")
        if label not in labels:
            labels.append(label)
    return f"Lưu ý: chưa lấy được dữ liệu {', '.join(labels)} do lỗi hệ thống; vui lòng thử lại sau."
