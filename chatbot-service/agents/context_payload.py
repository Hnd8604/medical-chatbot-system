"""Nén conversation_context thành payload gọn cho prompt LLM.

Dùng chung cho intent extractor, answer generator và summary generator để
logic truncate không bị lặp. Nhận object bất kỳ có ``memory_summary`` và
``recent_messages`` (pydantic ConversationContext) và trả dict thuần — các
agent không phụ thuộc schema của tầng api.
"""

from typing import Any


MAX_RECENT_MESSAGES = 6
MAX_MESSAGE_CHARS = 400


def compact_conversation_for_llm(
    context: Any,
    *,
    max_messages: int = MAX_RECENT_MESSAGES,
    max_chars: int = MAX_MESSAGE_CHARS,
) -> dict[str, Any] | None:
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

    if not memory_summary and not compact_messages:
        return None

    return {
        "memory_summary": memory_summary or "",
        "recent_messages": compact_messages,
    }
