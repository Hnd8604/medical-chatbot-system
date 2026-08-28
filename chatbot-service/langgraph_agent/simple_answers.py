"""Ba nhánh không chạm FHIR: general_chat, conversation_meta, unsupported.

Mỗi nhánh là một call LLM rẻ trả về ``{"answer": "..."}``. Nếu LLM lỗi thì rơi về
template tĩnh — ba loại câu này không bao giờ đáng để trả 503.

Payload trả về đúng shape mà Spring/frontend đang đọc, với ``evidence`` rỗng: không có
dữ liệu bệnh nhân nào được sinh ra ở đây.
"""

from __future__ import annotations

import logging
from typing import Any

from chat.response_builder import _zero_usage
from langgraph_agent.errors import AgentLlmError
from langgraph_agent.llm import STAGE_CHAT, call_llm, clean_text, parse_json_object
from langgraph_agent.prompts import (
    CONVERSATION_META_SYSTEM_PROMPT,
    GENERAL_CHAT_SYSTEM_PROMPT,
    UNSUPPORTED_SYSTEM_PROMPT,
)


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
CONVERSATION_META_FALLBACK = (
    "Mình chưa có đủ thông tin về cuộc trò chuyện này để trả lời câu hỏi đó."
)

_META_KIND_HINT = {
    "summary": "Người dùng muốn một bản tóm tắt cuộc trò chuyện cho tới hiện tại.",
    "last_answer": "Người dùng muốn biết câu trả lời gần nhất của bạn nói gì.",
    "current_context": "Người dùng muốn biết hiện đang trao đổi về bệnh nhân/chủ đề nào.",
}


async def build_general_chat_payload(
    *,
    message: str,
    model: str,
) -> tuple[dict[str, Any], dict[str, int | float]]:
    answer, usage, source = await _answer_or_fallback(
        system_prompt=GENERAL_CHAT_SYSTEM_PROMPT,
        user_payload={"message": message},
        model=model,
        fallback=GENERAL_CHAT_FALLBACK,
        source="llm_general_chat",
    )
    return _payload(answer, intent="general_chat", tool_name="general_chat", source=source), usage


async def build_unsupported_payload(
    *,
    message: str,
    model: str,
    safety_flag: str = "none",
) -> tuple[dict[str, Any], dict[str, int | float]]:
    answer, usage, source = await _answer_or_fallback(
        system_prompt=UNSUPPORTED_SYSTEM_PROMPT,
        user_payload={"message": message, "safety_flag": safety_flag},
        model=model,
        fallback=UNSUPPORTED_FALLBACK,
        source="llm_unsupported",
    )
    payload = _payload(answer, intent="unsupported", tool_name="unsupported_question", source=source)
    if safety_flag and safety_flag != "none":
        payload["safety_flag"] = safety_flag
    return payload, usage


async def build_conversation_meta_payload(
    *,
    message: str,
    meta_kind: str | None,
    conversation_context: dict[str, Any] | None,
    active_patient_id: str | None,
    model: str,
) -> tuple[dict[str, Any], dict[str, int | float]]:
    answer, usage, source = await _answer_or_fallback(
        system_prompt=CONVERSATION_META_SYSTEM_PROMPT,
        user_payload={
            "message": message,
            "meta_kind": meta_kind,
            "huong_dan": _META_KIND_HINT.get(meta_kind or "", ""),
            "context": conversation_context or {},
        },
        model=model,
        fallback=CONVERSATION_META_FALLBACK,
        source="llm_conversation_meta",
    )
    payload = _payload(
        answer,
        intent="conversation_meta",
        tool_name="conversation_context",
        source=source,
    )
    # Giữ lại bệnh nhân đang hoạt động để lượt sau vẫn hiểu "bệnh nhân này".
    payload["patient_id"] = active_patient_id
    return payload, usage


async def _answer_or_fallback(
    *,
    system_prompt: str,
    user_payload: Any,
    model: str,
    fallback: str,
    source: str,
) -> tuple[str, dict[str, int | float], str]:
    try:
        result = await call_llm(
            stage=STAGE_CHAT,
            model=model,
            system_prompt=system_prompt,
            user_payload=user_payload,
            max_output_tokens=400,
        )
        answer = clean_text(parse_json_object(result.content, stage=STAGE_CHAT).get("answer"))
        if answer:
            return answer, result.usage, source
        return fallback, result.usage, f"template_{source}"
    except AgentLlmError as exc:
        log.info("Nhánh %s rơi về template: %s", source, exc)
        return fallback, _zero_usage(), f"template_{source}"


def _payload(answer: str, *, intent: str, tool_name: str, source: str) -> dict[str, Any]:
    return {
        "answer": answer,
        "intent": intent,
        "patient_id": None,
        "evidence": [],
        "usage": _zero_usage(),
        "tool_name": tool_name,
        "answer_source": source,
    }
