"""Rolling summary hội thoại bằng LangGraph (context compression).

Thay vì gửi toàn bộ lịch sử hội thoại cho LLM, Spring chỉ gửi
``memory_summary`` (tóm tắt tích lũy) + cửa sổ ``recent_messages``. Module này
cập nhật tóm tắt đó: gộp summary cũ + cửa sổ recent + câu hỏi mới nhất thành
summary mới, chỉ khi hội thoại đã dài hơn cửa sổ (``total_message_count >=
summary_trigger_message_count``) — dưới ngưỡng thì recent_messages đã đủ ngữ
cảnh, không tốn token.

Summary chạy SONG SONG với answer generation (route tạo asyncio.Task) nên
KHÔNG chứa câu trả lời của lượt hiện tại; lượt đó sẽ nằm trong recent_messages
của lượt kế tiếp. Persist là việc của Spring (chat_sessions.memory_summary)
nên graph không dùng checkpointer.
"""

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Protocol, TypedDict

from langgraph.graph import END, START, StateGraph

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


class _SummaryState(TypedDict, total=False):
    previous_summary: str
    recent_messages: list[dict[str, str]]
    total_message_count: int | None
    latest_question: str
    patient_id: str | None
    summary: str
    usage: dict[str, int | float]
    source: str
    reason: str | None


class LangGraphSummaryGenerator:
    """StateGraph 2 nhánh: conditional edge quyết định summarize hay skip."""

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
        self._graph = self._build_graph()

    def _build_graph(self):
        graph = StateGraph(_SummaryState)
        graph.add_node("summarize", self._summarize_node)
        graph.add_conditional_edges(
            START,
            self._should_summarize,
            {"summarize": "summarize", "skip": END},
        )
        graph.add_edge("summarize", END)
        return graph.compile()

    def _should_summarize(self, state: _SummaryState) -> str:
        count = state.get("total_message_count")
        # >= (không phải >) vì count được đếm TRƯỚC khi lưu message hiện tại:
        # tại count == cửa sổ, recent_messages phủ toàn bộ history → summary
        # đầu tiên không bỏ sót message nào.
        if count is None or count < self.trigger_message_count:
            return "skip"
        if not state.get("recent_messages"):
            return "skip"
        return "summarize"

    async def _summarize_node(self, state: _SummaryState) -> _SummaryState:
        system_prompt = (
            "Bạn duy trì bản tóm tắt rolling của một hội thoại chatbot y tế. "
            "Gộp previous_summary với recent_messages và latest_question thành "
            "một bản tóm tắt mới, tối đa 120 từ, bằng tiếng Việt. "
            "Bắt buộc giữ lại: bệnh nhân đang được trao đổi (kèm Patient ID nếu có), "
            "các tài nguyên/chỉ số/thuốc/lần khám đã xem (kèm ID nếu có), "
            "và ý định gần nhất của người dùng. "
            "Không bịa thông tin, không thêm chẩn đoán mới, "
            "chỉ dùng nội dung được cung cấp. Trả về duy nhất đoạn tóm tắt."
        )
        user_payload = {
            "previous_summary": state.get("previous_summary") or "",
            "recent_messages": state.get("recent_messages") or [],
            "latest_question": state.get("latest_question") or "",
            "patient_id": state.get("patient_id"),
        }
        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
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
            return {"summary": "", "usage": dict(ZERO_USAGE), "source": "error", "reason": str(exc)}

        summary = (response.choices[0].message.content or "").strip()
        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) if response.usage else 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) if response.usage else 0,
            "estimated_cost_usd": 0,
        }
        if not summary:
            return {"summary": "", "usage": usage, "source": "error", "reason": "LLM returned an empty summary."}
        return {"summary": summary, "usage": usage, "source": "llm", "reason": None}

    async def summarize(
        self,
        *,
        conversation_context: dict[str, Any] | None,
        question: str,
        patient_id: str | None,
        total_message_count: int | None,
    ) -> SummaryResult:
        context = conversation_context or {}
        state: _SummaryState = {
            "previous_summary": context.get("memory_summary") or "",
            "recent_messages": context.get("recent_messages") or [],
            "total_message_count": total_message_count,
            "latest_question": question,
            "patient_id": patient_id,
        }
        try:
            result = await self._graph.ainvoke(state)
        except Exception as exc:  # phòng hờ lỗi ngoài node (không được fail /chat)
            log.warning("Summary graph failed: %s", exc)
            return SummaryResult(source="error", reason=str(exc))

        source = result.get("source") or "skipped"
        return SummaryResult(
            summary=result.get("summary") or "",
            usage=result.get("usage") or dict(ZERO_USAGE),
            source=source,
            reason=result.get("reason"),
        )


def get_summary_generator() -> SummaryGenerator:
    settings = get_settings()
    if settings.use_llm_summary and settings.llm_api_key:
        return LangGraphSummaryGenerator(
            api_key=settings.llm_api_key,
            model=settings.model_summary,
            timeout_seconds=settings.llm_request_timeout_seconds,
            base_url=settings.llm_base_url,
            trigger_message_count=settings.summary_trigger_message_count,
            max_output_tokens=settings.summary_max_output_tokens,
        )
    return NoopSummaryGenerator()
