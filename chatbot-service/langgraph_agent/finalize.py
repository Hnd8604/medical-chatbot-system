"""Khép lại một lượt của agent.

Nhánh FHIR dùng lại NGUYÊN ``chat/response_builder._finalize_chat_response`` của pipeline
``/chat``: terminology enrichment, answer LLM, lưu semantic cache, chờ rolling summary,
tính cost, dựng ``memory_update``. Đây là lý do hai đường không thể lệch nhau về
shape response hay về kế toán usage — bản tham chiếu fork một finalizer riêng và
đánh mất cả cache lẫn cost.

Nhánh không-FHIR (general_chat / conversation_meta / unsupported) đã có câu trả lời cuối
rồi, nên chỉ cần kế toán + memory, không gọi answer LLM lần nữa.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

from agents.answer_generator import AnswerGenerator, AnswerResult, combine_usage
from agents.intent.models import IntentPlan
from agents.summary_generator import SummaryResult
from app.config import get_settings
from chat.response_builder import (
    _apply_estimated_cost,
    _build_memory_update,
    _finalize_chat_response,
    _zero_usage,
)
from langgraph_agent.evidence_budget import apply_evidence_budget
from langgraph_agent.state import ANSWER_MODE_DATA_ONLY


@dataclass
class _TemplateAnswerGenerator:
    """Fast-path: trả thẳng câu template do tool dựng, không gọi answer LLM.

    Nhét qua cửa ``AnswerGenerator`` thay vì rẽ nhánh trong ``_finalize_chat_response``
    để phần còn lại của luồng (cost, memory, summary) không phải biết gì về fast-path.
    Vì ``source != "llm"`` nên lượt này cũng không ghi vào semantic cache — đúng ý:
    cache tồn tại để tiết kiệm LLM call, mà lượt này vốn đã không tốn call nào.
    """

    async def generate(self, **kwargs: Any) -> AnswerResult:
        return AnswerResult(
            answer=kwargs.get("fallback_answer") or "",
            source="template_fast_path",
            usage=_zero_usage(),
        )


def should_use_template_fast_path(
    *,
    payload: dict[str, Any],
    answer_mode: str,
    step_count: int,
) -> bool:
    settings = get_settings()
    if not settings.agent_template_fast_path:
        return False
    if answer_mode != ANSWER_MODE_DATA_ONLY or step_count != 1:
        return False
    evidence = payload.get("evidence") or []
    if not evidence or len(evidence) > settings.agent_template_fast_path_max_evidence:
        return False
    return bool((payload.get("answer") or "").strip())


async def finalize_fhir_payload(
    payload: dict[str, Any],
    *,
    question: str,
    plan: IntentPlan,
    answer_generator: AnswerGenerator,
    model: str,
    query_complexity: str,
    routing_source: str,
    router_usage: dict[str, int | float],
    summary_task: "asyncio.Task[SummaryResult] | None",
    conversation_context: dict[str, Any] | None,
    answer_mode: str,
    step_count: int,
) -> dict[str, Any]:
    settings = get_settings()

    evidence, prune_stats = apply_evidence_budget(
        payload.get("evidence") or [],
        max_chars=settings.agent_evidence_max_chars,
    )
    payload["evidence"] = evidence

    generator: AnswerGenerator = answer_generator
    if should_use_template_fast_path(
        payload=payload,
        answer_mode=answer_mode,
        step_count=step_count,
    ):
        generator = _TemplateAnswerGenerator()

    finalized = await _finalize_chat_response(
        payload,
        question,
        plan,
        generator,
        model=model,
        query_complexity=query_complexity,
        routing_source=routing_source,
        router_usage=router_usage,
        summary_task=summary_task,
        conversation_context=conversation_context,
    )
    if prune_stats.get("dropped_items") or prune_stats.get("pruned_fields"):
        finalized["evidence_pruning"] = prune_stats
    return finalized


async def finalize_simple_payload(
    payload: dict[str, Any],
    *,
    plan: IntentPlan,
    model: str,
    query_complexity: str,
    routing_source: str,
    usages: list[dict[str, int | float] | None],
    summary_task: "asyncio.Task[SummaryResult] | None",
) -> dict[str, Any]:
    """Khép lượt không dùng FHIR: câu trả lời đã có, chỉ cần kế toán + memory."""
    settings = get_settings()
    summary_result = await summary_task if summary_task else SummaryResult()

    payload["tool_name"] = payload.get("tool_name") or plan.tool_name
    payload["intent_source"] = plan.source
    payload["llm_provider"] = settings.llm_provider
    payload["llm_model"] = model
    payload["query_complexity"] = query_complexity
    payload["routing_source"] = routing_source
    payload["answer_usage"] = combine_usage(*[u for u in usages if u])
    payload["summary_usage"] = summary_result.usage
    payload["usage"] = combine_usage(*[u for u in usages if u], summary_result.usage)
    _apply_estimated_cost(payload)

    memory_update = _build_memory_update(payload, plan, summary=summary_result.summary)
    if memory_update:
        payload["memory_update"] = memory_update
    return payload
