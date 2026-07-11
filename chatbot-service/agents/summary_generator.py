"""Rolling summary hội thoại (context compression).

Thay vì gửi toàn bộ lịch sử hội thoại cho LLM, Spring chỉ gửi
``memory_summary`` (tóm tắt tích lũy) + cửa sổ ``recent_messages``. Module này
cập nhật tóm tắt đó: gộp summary cũ + cửa sổ recent + câu hỏi mới nhất thành
summary mới, chỉ khi hội thoại đã dài hơn cửa sổ (``total_message_count >=
summary_trigger_message_count``) — dưới ngưỡng thì recent_messages đã đủ ngữ
cảnh, không tốn token.

Summary chạy SONG SONG với answer generation (route tạo asyncio.Task) nên
KHÔNG chứa câu trả lời của lượt hiện tại; lượt đó sẽ nằm trong recent_messages
của lượt kế tiếp. Persist là việc của Spring (chat_sessions.memory_summary).

Đây là một lệnh gọi LLM đơn (một quyết định trigger + một call) nên viết bằng
async thuần, không cần graph engine.
"""

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Protocol

from agents.answer_generator import ZERO_USAGE
from agents.gateway_context import gateway_call_kwargs
from app.config import get_settings


log = logging.getLogger("summary_generator")


@dataclass(frozen=True)
class SummaryResult:
    summary: str = ""
    usage: dict[str, int | float] = field(default_factory=lambda: dict(ZERO_USAGE))
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
    """Dùng khi tắt summary hoặc không có LiteLLM key (demo không LLM)."""

    async def summarize(
        self,
        *,
        conversation_context: dict[str, Any] | None,
        question: str,
        patient_id: str | None,
        total_message_count: int | None,
    ) -> SummaryResult:
        return SummaryResult(source="disabled")


_SYSTEM_PROMPT = (
    "Bạn duy trì bản tóm tắt rolling của một hội thoại chatbot y tế. "
    "Gộp previous_summary với recent_messages và latest_question thành "
    "một bản tóm tắt mới, tối đa 120 từ, bằng tiếng Việt. "
    "Bắt buộc giữ lại: bệnh nhân đang được trao đổi (kèm Patient ID nếu có), "
    "các tài nguyên/chỉ số/thuốc/lần khám đã xem (kèm ID nếu có), "
    "và ý định gần nhất của người dùng. "
    "Không bịa thông tin, không thêm chẩn đoán mới, "
    "chỉ dùng nội dung được cung cấp. Trả về duy nhất đoạn tóm tắt."
)


class LlmSummaryGenerator:
    """Rolling summary bằng một lệnh gọi LLM qua LiteLLM gateway."""

    def __init__(
        self,
        api_key: str,
        model: str,
        timeout_seconds: float,
        base_url: str | None = None,
        trigger_message_count: int = 6,
        max_output_tokens: int = 256,
    ) -> None:
        from openai import AsyncOpenAI

        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)
        self.model = model
        self.trigger_message_count = trigger_message_count
        self.max_output_tokens = max_output_tokens

    def _should_summarize(
        self,
        total_message_count: int | None,
        recent_messages: list[dict[str, str]],
    ) -> bool:
        # >= (không phải >) vì count được đếm TRƯỚC khi lưu message hiện tại:
        # tại count == cửa sổ, recent_messages phủ toàn bộ history → summary
        # đầu tiên không bỏ sót message nào.
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

        user_payload = {
            "previous_summary": context.get("memory_summary") or "",
            "recent_messages": recent_messages,
            "latest_question": question,
            "patient_id": patient_id,
        }
        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
                ],
                temperature=0.2,
                max_tokens=self.max_output_tokens,
                **gateway_call_kwargs(),
            )
        except Exception as exc:
            # Summary là việc phụ chạy song song với answer: mọi lỗi (kể cả
            # budget) chỉ log warning, KHÔNG raise_if_budget_exceeded — fail cả
            # /chat để vứt answer đã sinh là tệ hơn; lượt kế tiếp sẽ bị chặn
            # 429 ở intent/answer call.
            log.warning("Summary LLM call failed: %s", exc)
            return SummaryResult(source="error", reason=str(exc))

        summary = (response.choices[0].message.content or "").strip()
        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) if response.usage else 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) if response.usage else 0,
            "estimated_cost_usd": 0,
        }
        if not summary:
            return SummaryResult(usage=usage, source="error", reason="LLM returned an empty summary.")
        return SummaryResult(summary=summary, usage=usage, source="llm")


def get_summary_generator() -> SummaryGenerator:
    settings = get_settings()
    if settings.use_llm_summary and settings.llm_api_key:
        return LlmSummaryGenerator(
            api_key=settings.llm_api_key,
            model=settings.model_summary,
            timeout_seconds=settings.llm_request_timeout_seconds,
            base_url=settings.llm_base_url,
            trigger_message_count=settings.summary_trigger_message_count,
            max_output_tokens=settings.summary_max_output_tokens,
        )
    return NoopSummaryGenerator()
