"""Ngữ cảnh hội thoại cho prompt: nén conversation_context và gợi ý bệnh nhân đang chọn."""

from __future__ import annotations

from typing import Any

from api.schemas import ChatRequest
from nlp.text import normalize_patient_id


MAX_RECENT_MESSAGES = 6
MAX_MESSAGE_CHARS = 400


def compact_conversation_for_llm(
    context: Any,
    *,
    max_messages: int = MAX_RECENT_MESSAGES,
    max_chars: int = MAX_MESSAGE_CHARS,
) -> dict[str, Any] | None:
    """Payload gọn cho prompt: summary + tối đa ``max_messages`` tin gần nhất, mỗi tin cắt
    ``max_chars`` ký tự. Giữ cả ID ngữ cảnh (active patient / last resource) vì router,
    planner và plan cache dựa vào chúng để hiểu "bệnh nhân này", "thuốc đó"."""
    if context is None:
        return None

    memory_summary = getattr(context, "memory_summary", None)
    recent_messages = getattr(context, "recent_messages", None) or []

    compact_messages: list[dict[str, str]] = []
    for message in recent_messages[-max_messages:]:
        role = getattr(message, "role", None)
        content = getattr(message, "content", None)
        if not isinstance(role, str) or not isinstance(content, str):
            continue
        compact_messages.append({"role": role, "content": content[:max_chars]})

    compact: dict[str, Any] = {}
    if memory_summary:
        compact["memory_summary"] = memory_summary
    if compact_messages:
        compact["recent_messages"] = compact_messages
    for key in ("active_patient_id", "last_intent", "last_tool_name", "last_resource_type", "last_resource_id"):
        value = getattr(context, key, None)
        if value:
            compact[key] = value
    return compact or None


def patient_id_hint(request: ChatRequest) -> str | None:
    """Bệnh nhân đang chọn: ưu tiên patient_id Spring gửi, rồi active_patient_id của session."""
    request_patient_id = normalize_patient_id(request.patient_id)
    if request_patient_id:
        return request_patient_id
    if request.conversation_context:
        return normalize_patient_id(request.conversation_context.active_patient_id)
    return None
