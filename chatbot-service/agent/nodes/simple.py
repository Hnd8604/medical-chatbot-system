"""Các nhánh không chạm FHIR: general_chat, conversation_meta, unsupported, clarify.

Ba nhánh đầu là một call LLM rẻ trả ``{"answer": "..."}``; LLM lỗi thì rơi về template
tĩnh — các loại câu này không bao giờ đáng để trả 503. Ở low-cost mode cả ba dùng thẳng
template (``conversation_meta`` dựng câu trả lời từ summary/tin nhắn gần nhất), 0 LLM call. ``clarify`` là template thuần
(không lập được plan, hoặc plan bị validator loại vì hỏng). ``evidence`` luôn rỗng: không có dữ
liệu bệnh nhân nào được sinh ra ở đây.
"""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.answer.finalize import TurnMeta, finalize_simple
from agent.execution.executor import clarification_payload
from agent.prompts import (
    CONVERSATION_META_SYSTEM_PROMPT,
    GENERAL_CHAT_SYSTEM_PROMPT,
    UNSUPPORTED_SYSTEM_PROMPT,
)
from agent.state import (
    RESPONSE_STATUS_ANSWERED,
    RESPONSE_STATUS_CLARIFICATION,
    STAGE_CHAT,
    STAGE_PLANNER,
    STAGE_ROUTER,
    STAGE_SUMMARY,
    AgentState,
    get_deps,
)
from app.config import get_settings
from llm.client import LlmCallError, call_llm, clean_text, parse_json_object
from llm.usage import zero_usage


log = logging.getLogger(__name__)

GENERAL_CHAT_FALLBACK = (
    "Xin chào! Mình hỗ trợ tra cứu dữ liệu y tế đã ghi nhận trong hồ sơ: thông tin bệnh nhân, "
    "chỉ số/xét nghiệm, chẩn đoán, lần khám và thuốc. Bạn cho mình biết tên hoặc mã bệnh nhân nhé."
)
UNSUPPORTED_FALLBACK = (
    "Mình chỉ có thể tra cứu lại dữ liệu đã ghi nhận trong hồ sơ, không thể chẩn đoán, kê đơn "
    "hay tư vấn điều trị. Với các vấn đề đó, bạn nên trao đổi trực tiếp với bác sĩ điều trị. "
    "Mình có thể giúp bạn xem chẩn đoán, thuốc, chỉ số hoặc lần khám đã được ghi nhận."
)
CONVERSATION_META_FALLBACK = "Mình chưa có đủ thông tin về cuộc trò chuyện này để trả lời câu hỏi đó."
UNRECOGNIZED_QUESTION_ANSWER = (
    "Mình chưa xác định được cần lấy loại dữ liệu nào. "
    "Bạn có thể hỏi về thông tin bệnh nhân, chỉ số/xét nghiệm, chẩn đoán, lần khám hoặc thuốc, "
    "và nên kèm tên hoặc mã bệnh nhân nếu có."
)

_META_RECENT_QUESTIONS = 3

_META_KIND_HINT = {
    "summary": "Người dùng muốn một bản tóm tắt cuộc trò chuyện cho tới hiện tại.",
    "last_answer": "Người dùng muốn biết câu trả lời gần nhất của bạn nói gì.",
    "current_context": "Người dùng muốn biết hiện đang trao đổi về bệnh nhân/chủ đề nào.",
}


async def general_chat(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    answer, usage, source = await _answer_or_fallback(
        state,
        system_prompt=GENERAL_CHAT_SYSTEM_PROMPT,
        user_payload={"message": state["request"].message},
        fallback=GENERAL_CHAT_FALLBACK,
        source="llm_general_chat",
    )
    payload = _payload(answer, intent="general_chat", source=source)
    return await finish_simple_turn(state, config, payload, usage, tool_name="general_chat")


async def unsupported(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    safety_flag = state.get("route_safety_flag") or "none"
    answer, usage, source = await _answer_or_fallback(
        state,
        system_prompt=UNSUPPORTED_SYSTEM_PROMPT,
        user_payload={"message": state["request"].message, "safety_flag": safety_flag},
        fallback=UNSUPPORTED_FALLBACK,
        source="llm_unsupported",
    )
    payload = _payload(answer, intent="unsupported", source=source)
    if safety_flag != "none":
        payload["safety_flag"] = safety_flag
    return await finish_simple_turn(state, config, payload, usage, tool_name="unsupported_question")


async def conversation_meta(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    context = state.get("conversation_context") or {}
    meta_kind = state.get("route_meta_kind")
    answer, usage, source = await _answer_or_fallback(
        state,
        system_prompt=CONVERSATION_META_SYSTEM_PROMPT,
        user_payload={
            "message": state["request"].message,
            "meta_kind": meta_kind,
            "huong_dan": _META_KIND_HINT.get(meta_kind or "", ""),
            "context": context,
        },
        fallback=conversation_meta_template(context, meta_kind) or CONVERSATION_META_FALLBACK,
        source="llm_conversation_meta",
    )
    payload = _payload(answer, intent="conversation_meta", source=source)
    # Giữ lại bệnh nhân đang hoạt động để lượt sau vẫn hiểu "bệnh nhân này".
    payload["patient_id"] = context.get("active_patient_id") or state.get("provided_patient_id")
    return await finish_simple_turn(state, config, payload, usage, tool_name="conversation_context")


async def clarify(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    payload = {**clarification_payload(UNRECOGNIZED_QUESTION_ANSWER), "answer_source": "template_clarification"}
    if state.get("plan_error"):
        payload["answer_reason"] = state["plan_error"]
    return await finish_simple_turn(
        state,
        config,
        payload,
        zero_usage(),
        tool_name="unsupported_question",
        status=RESPONSE_STATUS_CLARIFICATION,
    )


async def finish_simple_turn(
    state: AgentState,
    config: RunnableConfig,
    payload: dict[str, Any],
    usage: dict[str, int | float],
    *,
    tool_name: str,
    status: str = RESPONSE_STATUS_ANSWERED,
) -> dict[str, Any]:
    deps = get_deps(config)
    deps.record(STAGE_CHAT, usage)
    route_source = state.get("route_source") or "llm"
    finalized = await finalize_simple(
        payload,
        meta=TurnMeta(
            tool_name=tool_name,
            intent=payload["intent"],
            source=f"agent_{route_source}",
            # Planner có thể đã tốn token trước khi plan bị loại ở validate.
            usage=deps.usage_of(STAGE_ROUTER, STAGE_PLANNER, STAGE_CHAT),
        ),
        model=get_settings().chat_model,
        routing_source=route_source,
        summary_task=deps.summary_task,
    )
    deps.record(STAGE_SUMMARY, finalized.get("summary_usage"))
    finalized["response_status"] = status
    return {"payload": finalized, "response_status": status}


def conversation_meta_template(context: dict[str, Any], meta_kind: str | None) -> str | None:
    """Câu trả lời tất định cho câu hỏi về cuộc trò chuyện, chỉ từ ngữ cảnh Spring gửi."""
    messages = context.get("recent_messages") or []
    if meta_kind == "summary":
        if context.get("memory_summary"):
            return f"Tóm tắt cuộc trò chuyện tới hiện tại: {context['memory_summary']}"
        questions = [m["content"] for m in messages if m.get("role") == "user"][-_META_RECENT_QUESTIONS:]
        if questions:
            return "Các câu bạn đã hỏi gần đây:\n" + "\n".join(f"- {q}" for q in questions)
        return None
    if meta_kind == "last_answer":
        last_question = next((m["content"] for m in reversed(messages) if m.get("role") == "user"), None)
        last_answer = next((m["content"] for m in reversed(messages) if m.get("role") == "assistant"), None)
        if not last_answer:
            return None
        prefix = f"Bạn vừa hỏi: \"{last_question}\"\n" if last_question else ""
        return f"{prefix}Câu trả lời gần nhất của mình: {last_answer}"
    if meta_kind == "current_context":
        patient_id = context.get("active_patient_id")
        if not patient_id:
            return "Hiện chưa có bệnh nhân nào được chọn trong cuộc trò chuyện này."
        return f"Hiện mình đang trao đổi về bệnh nhân {patient_id}."
    return None


async def _answer_or_fallback(
    state: AgentState,
    *,
    system_prompt: str,
    user_payload: Any,
    fallback: str,
    source: str,
) -> tuple[str, dict[str, int | float], str]:
    if state.get("low_cost_mode"):
        return fallback, zero_usage(), f"template_low_cost_{source.removeprefix('llm_')}"
    try:
        result = await call_llm(
            stage=STAGE_CHAT,
            model=get_settings().chat_model,
            system_prompt=system_prompt,
            user_payload=user_payload,
            max_output_tokens=400,
        )
        answer = clean_text(parse_json_object(result.content, stage=STAGE_CHAT).get("answer"))
        if answer:
            return answer, result.usage, source
        return fallback, result.usage, f"template_{source}"
    except LlmCallError as exc:
        log.info("Nhánh %s rơi về template: %s", source, exc)
        return fallback, zero_usage(), f"template_{source}"


def _payload(answer: str, *, intent: str, source: str) -> dict[str, Any]:
    return {
        "answer": answer,
        "intent": intent,
        "patient_id": None,
        "evidence": [],
        "answer_source": source,
    }
