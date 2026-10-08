"""Rolling summary hội thoại (context compression).

Thay vì gửi toàn bộ lịch sử hội thoại cho LLM, Spring chỉ gửi ``memory_summary`` (tóm
tắt tích luỹ) + cửa sổ ``recent_messages``. Module này cập nhật tóm tắt đó, chỉ khi hội
thoại đã dài hơn cửa sổ (``total_message_count >= summary_trigger_message_count``).

Summary chạy SONG SONG với cả lượt (graph tạo ``asyncio.Task``) nên KHÔNG chứa câu trả
lời của lượt hiện tại; lượt đó sẽ nằm trong recent_messages của lượt kế tiếp. Persist là
việc của Spring (chat_sessions.memory_summary).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Protocol

from agent.prompts import SUMMARY_SYSTEM_PROMPT
from agent.state import STAGE_SUMMARY
from app.config import get_settings
from llm.client import GatewayBudgetExceededError, LlmCallError, call_llm
from llm.usage import zero_usage


log = logging.getLogger(__name__)


@dataclass(frozen=True)
class SummaryResult:
    summary: str = ""
    usage: dict[str, int | float] = field(default_factory=zero_usage)
    source: str = "skipped"  # llm | skipped | disabled | error
    reason: str | None = None


class SummaryGenerator(Protocol):
    async def summarize(
        self,
        *,
        conversation_context: dict[str, Any] | None,
        question: str,
        patient_id: str | None,
        total_message_count: int | None,
    ) -> SummaryResult:
        ...


class NoopSummaryGenerator:
    """Dùng khi tắt summary hoặc không có gateway (demo không LLM)."""

    async def summarize(self, **kwargs: Any) -> SummaryResult:
        return SummaryResult(source="disabled")


class LlmSummaryGenerator:
    def __init__(self, *, model: str, trigger_message_count: int = 8, max_output_tokens: int = 256) -> None:
        self.model = model
        self.trigger_message_count = trigger_message_count
        self.max_output_tokens = max_output_tokens

    def _should_summarize(self, total_message_count: int | None, recent_messages: list[Any]) -> bool:
        # >= (không phải >) vì count được đếm TRƯỚC khi lưu message hiện tại: tại
        # count == cửa sổ, recent_messages phủ toàn bộ history → không bỏ sót message nào.
        if total_message_count is None or total_message_count < self.trigger_message_count:
            return False
        return bool(recent_messages)

    async def summarize(
        self,
        *,
        conversation_context: dict[str, Any] | None,
        question: str,
        patient_id: str | None,
        total_message_count: int | None,
    ) -> SummaryResult:
        context = conversation_context or {}
        recent_messages = context.get("recent_messages") or []
        if not self._should_summarize(total_message_count, recent_messages):
            return SummaryResult(source="skipped")

        try:
            result = await call_llm(
                stage=STAGE_SUMMARY,
                model=self.model,
                system_prompt=SUMMARY_SYSTEM_PROMPT,
                user_payload={
                    "previous_summary": context.get("memory_summary") or "",
                    "recent_messages": recent_messages,
                    "latest_question": question,
                    "patient_id": patient_id,
                },
                max_output_tokens=self.max_output_tokens,
                temperature=0.2,
            )
        except (LlmCallError, GatewayBudgetExceededError) as exc:
            # Summary là việc phụ: mọi lỗi (kể cả budget) chỉ log warning — làm hỏng cả
            # lượt chat để vứt answer đã sinh là tệ hơn; lượt kế tiếp sẽ bị chặn 429 ở router.
            log.warning("Summary LLM call failed: %s", exc)
            return SummaryResult(source="error", reason=str(exc))

        summary = result.content.strip()
        if not summary:
            return SummaryResult(usage=result.usage, source="error", reason="LLM returned an empty summary.")
        return SummaryResult(summary=summary, usage=result.usage, source="llm")


def get_summary_generator() -> SummaryGenerator:
    settings = get_settings()
    if settings.use_llm_summary:
        return LlmSummaryGenerator(
            model=settings.model_summary,
            trigger_message_count=settings.summary_trigger_message_count,
            max_output_tokens=settings.summary_max_output_tokens,
        )
    return NoopSummaryGenerator()
