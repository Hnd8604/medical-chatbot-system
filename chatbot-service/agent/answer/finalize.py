"""Khép lại một lượt: sinh câu trả lời, ghi cache, kế toán usage, dựng memory_update.

Hai lối ra:

- ``finalize_answer``: nhánh FHIR — evidence budget → terminology (nếu cần giải thích)
  → answer LLM (hoặc template fast-path) → grounding check → ghi semantic cache → chờ
  rolling summary → usage → ``memory_update``. Low-cost mode: evidence budget nhỏ hơn,
  bỏ terminology, template fast-path luôn được xét.
- ``finalize_simple``: nhánh không-FHIR (general_chat / conversation_meta / unsupported /
  clarification) — câu trả lời đã có sẵn, chỉ còn kế toán + memory.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from agent.answer.evidence_budget import apply_evidence_budget
from agent.answer.generator import AnswerGenerator, TemplateAnswerGenerator
from agent.answer.grounding import check_answer
from agent.memory.summary import SummaryResult
from agent.state import ANSWER_MODE_DATA_ONLY
from app.config import get_settings
from llm.model_select import ModelChoice
from llm.models import provider_and_model
from llm.usage import combine_usage, zero_usage
from terminology.enrichment_service import get_enrichment_service

if TYPE_CHECKING:
    from cache.semantic_cache import SemanticCacheService


log = logging.getLogger(__name__)

# Khoá patient của semantic cache khi lượt chat không gắn bệnh nhân nào. Chỉ dùng làm
# key, không bao giờ lộ ra response/memory_update.
NO_PATIENT_CACHE_KEY = "__no_patient__"


@dataclass(frozen=True)
class TurnMeta:
    """Metadata của lượt mà response và memory_update cần."""

    tool_name: str
    intent: str = "unknown"
    patient_id: str | None = None
    explain: bool = False
    all_patients: bool = False
    # intent_source: cách lập kế hoạch lượt này (agent_llm / agent_plan_cache / agent_rule_fallback...).
    source: str = "agent"
    # Token đã tiêu trước bước answer (router + planner + chat).
    usage: dict[str, int | float] = field(default_factory=zero_usage)


@dataclass(frozen=True)
class CacheTarget:
    """Nơi ghi semantic cache sau khi sinh được câu trả lời LLM."""

    service: "SemanticCacheService"
    user_id: str


async def finalize_answer(
    payload: dict[str, Any],
    *,
    question: str,
    meta: TurnMeta,
    answer_generator: AnswerGenerator,
    model_choice: ModelChoice,
    summary_task: "asyncio.Task[SummaryResult] | None",
    conversation_context: dict[str, Any] | None,
    answer_mode: str,
    step_count: int,
    cache: CacheTarget | None,
    low_cost: bool = False,
) -> dict[str, Any]:
    settings = get_settings()
    _apply_turn_metadata(payload, meta, model=model_choice.model)
    payload["query_complexity"] = model_choice.complexity
    payload["routing_source"] = model_choice.source
    payload["grounding"] = _grounding(checked=False)

    if payload.get("needs_patient_selection"):
        # Chưa chốt được bệnh nhân → không sinh câu trả lời, không cập nhật memory.
        payload["answer_source"] = "template_patient_selection"
        payload["answer_usage"] = zero_usage()
        payload["summary_usage"] = zero_usage()
        payload["usage"] = combine_usage(meta.usage)
        payload["pending_question"] = question
        if summary_task and not summary_task.done():
            summary_task.cancel()
        return payload

    evidence, prune_stats = apply_evidence_budget(
        payload.get("evidence") or [],
        max_chars=settings.low_cost_evidence_limit() if low_cost else settings.evidence_max_chars,
    )
    payload["evidence"] = evidence
    if prune_stats.get("dropped_items") or prune_stats.get("pruned_fields"):
        payload["evidence_pruning"] = prune_stats

    # Terminology CHỈ khi câu hỏi mang ý "giải thích" — câu hỏi dữ liệu thuần không gọi
    # API ngoài. explain_concept đã tự gắn external_knowledge (không có FHIR evidence).
    # Low-cost mode bỏ bước này: kết quả của nó làm phình prompt answer.
    external_knowledge = payload.get("external_knowledge") or await enrich_terminology(
        evidence, meta.explain and not low_cost
    )
    if external_knowledge:
        payload["external_knowledge"] = external_knowledge

    generator = answer_generator
    if _use_template_fast_path(payload, answer_mode=answer_mode, step_count=step_count, low_cost=low_cost):
        generator = TemplateAnswerGenerator(source="template_low_cost" if low_cost else "template_fast_path")

    template_answer = payload.get("answer") or ""
    answer = await generator.generate(
        question=question,
        intent=payload.get("intent") or meta.intent,
        tool_name=meta.tool_name,
        patient_id=payload.get("patient_id") or meta.patient_id,
        evidence=evidence,
        fallback_answer=template_answer,
        model=model_choice.model,
        conversation_context=conversation_context,
        external_knowledge=external_knowledge,
    )
    payload["answer"] = answer.answer
    payload["answer_source"] = answer.source
    payload["answer_usage"] = answer.usage
    if answer.reason:
        payload["answer_reason"] = answer.reason
    # Model thực tế từ response (phản ánh cả fallback của gateway) — model "chính" của lượt
    # cho audit/hiển thị. Cost theo từng model nằm ở llm_calls.
    if answer.model:
        payload["llm_provider"], payload["llm_model"] = provider_and_model(answer.model)

    grounded = True
    if answer.source == "llm" and settings.grounding_check_mode != "off":
        grounded = _apply_grounding(
            payload,
            question=question,
            patient_id=payload.get("patient_id") or meta.patient_id,
            template_answer=template_answer,
            enforce=settings.grounding_check_mode == "enforce",
        )

    # Chỉ cache câu LLM đã qua grounding — cache là chỗ một câu bịa bị nhân bản.
    if payload["answer_source"] == "llm" and grounded and cache is not None:
        await _save_to_cache(cache, payload, meta, question, combine_usage(meta.usage, answer.usage))

    summary = await _await_summary(summary_task)
    payload["summary_usage"] = summary.usage
    if summary.source == "error" and summary.reason:
        payload["summary_reason"] = summary.reason

    # usage tổng gộp token + cost thật (gateway) của router/planner/answer/summary.
    # Chi tiết theo từng call/model: payload["llm_calls"] (graph.run_agent gắn).
    payload["usage"] = combine_usage(meta.usage, answer.usage, summary.usage)
    memory_update = build_memory_update(payload, meta, summary=summary.summary)
    if memory_update:
        payload["memory_update"] = memory_update
    return payload


async def finalize_simple(
    payload: dict[str, Any],
    *,
    meta: TurnMeta,
    model: str,
    routing_source: str,
    summary_task: "asyncio.Task[SummaryResult] | None",
) -> dict[str, Any]:
    _apply_turn_metadata(payload, meta, model=model)
    payload["query_complexity"] = "simple"
    payload["routing_source"] = routing_source
    payload["grounding"] = _grounding(checked=False)
    summary = await _await_summary(summary_task)
    payload["answer_usage"] = combine_usage(meta.usage)
    payload["summary_usage"] = summary.usage
    payload["usage"] = combine_usage(meta.usage, summary.usage)
    memory_update = build_memory_update(payload, meta, summary=summary.summary)
    if memory_update:
        payload["memory_update"] = memory_update
    return payload


async def enrich_terminology(evidence: list[dict[str, Any]], explain: bool) -> list[dict[str, Any]]:
    """Giải thích mã y khoa trong evidence. Mọi lỗi được nuốt để không chặn câu trả lời."""
    if not explain or not evidence:
        return []
    try:
        items = await get_enrichment_service().enrich_payload({"evidence": evidence}, language="en")
    except Exception:
        log.exception("Terminology enrichment error")
        return []
    return [item.as_dict() for item in items]


def build_memory_update(
    payload: dict[str, Any],
    meta: TurnMeta,
    summary: str = "",
) -> dict[str, Any] | None:
    """Cấu trúc memory cho Spring merge vào chat_sessions.

    ``summary`` rỗng nghĩa là lượt này không trigger rolling summary — Spring giữ summary
    cũ qua firstNonBlank(memory_update.summary, current).
    """
    if payload.get("needs_patient_selection"):
        return None

    evidence_refs = _evidence_refs(payload.get("evidence") or [])
    patient_id = payload.get("patient_id")
    all_patients = bool(payload.get("all_patients"))
    last_intent = payload.get("intent") or meta.intent

    if (all_patients and not evidence_refs) or (not patient_id and not evidence_refs):
        if not summary and not all_patients:
            return None
        return {
            "last_intent": last_intent,
            "last_tool_name": meta.tool_name,
            "summary": summary,
            "evidence_refs": [],
        }

    first_ref = evidence_refs[0] if evidence_refs else {}
    return {
        "active_patient_id": patient_id if not all_patients else None,
        "last_intent": last_intent,
        "last_tool_name": meta.tool_name,
        "last_resource_type": first_ref.get("resource_type"),
        "last_resource_id": first_ref.get("resource_id"),
        "summary": summary,
        "evidence_refs": evidence_refs,
    }


def _apply_grounding(
    payload: dict[str, Any],
    *,
    question: str,
    patient_id: str | None,
    template_answer: str,
    enforce: bool,
) -> bool:
    """Kiểm câu LLM với evidence; enforce + fail ⇒ thay bằng câu template. Trả passed."""
    result = check_answer(
        payload["answer"],
        evidence=payload.get("evidence") or [],
        question=question,
        patient_id=patient_id,
        external_knowledge=payload.get("external_knowledge"),
        template_answer=template_answer,
    )
    payload["grounding"] = _grounding(checked=True, passed=result.passed)
    if result.passed:
        return True
    # Chỉ log loại vi phạm, không log giá trị (có thể là định danh bệnh nhân).
    log.warning(
        "Answer LLM không bám evidence (%s): %s",
        "enforce" if enforce else "log",
        sorted({violation["kind"] for violation in result.violations}),
    )
    if enforce and template_answer.strip():
        payload["answer"] = template_answer
        payload["answer_source"] = "template_grounding_fallback"
        payload["answer_reason"] = "Câu trả lời LLM chứa dữ kiện không có trong evidence."
    return False


def _grounding(*, checked: bool, passed: bool | None = None) -> dict[str, Any]:
    return {"checked": checked, "passed": passed}


def _apply_turn_metadata(payload: dict[str, Any], meta: TurnMeta, *, model: str) -> None:
    payload["tool_name"] = meta.tool_name
    payload["intent_source"] = meta.source
    payload["llm_provider"], payload["llm_model"] = provider_and_model(model)
    payload.setdefault("intent", meta.intent)
    payload.setdefault("patient_id", meta.patient_id)
    payload.setdefault("evidence", [])
    if meta.all_patients:
        payload["all_patients"] = True


def _use_template_fast_path(
    payload: dict[str, Any], *, answer_mode: str, step_count: int, low_cost: bool = False
) -> bool:
    """Plan 1 bước, chỉ hỏi dữ liệu, ít evidence ⇒ câu template đã đủ, khỏi gọi answer LLM.

    Bật bằng ``TEMPLATE_FAST_PATH``; low-cost mode luôn xét, bất kể cờ đó.
    """
    settings = get_settings()
    if not (settings.template_fast_path or low_cost):
        return False
    if answer_mode != ANSWER_MODE_DATA_ONLY or step_count != 1:
        return False
    evidence = payload.get("evidence") or []
    if not evidence or len(evidence) > settings.template_fast_path_max_evidence:
        return False
    return bool((payload.get("answer") or "").strip())


async def _save_to_cache(
    cache: CacheTarget,
    payload: dict[str, Any],
    meta: TurnMeta,
    question: str,
    usage: dict[str, int | float],
) -> None:
    # saved_usage khi hit đại diện cho chi phí router+planner+answer tiết kiệm được;
    # summary vẫn phải chạy theo hội thoại nên không tính.
    try:
        await cache.service.save_to_cache(
            user_id=cache.user_id,
            patient_id=payload.get("patient_id") or meta.patient_id or NO_PATIENT_CACHE_KEY,
            intent=payload.get("intent") or meta.intent,
            question=question,
            answer=payload["answer"],
            usage=usage,
            llm_provider=payload.get("llm_provider"),
            llm_model=payload.get("llm_model"),
        )
    except Exception:
        log.exception("Cache save error")


async def _await_summary(summary_task: "asyncio.Task[SummaryResult] | None") -> SummaryResult:
    # Task tự nuốt mọi lỗi và luôn trả SummaryResult; chỉ có thể bị huỷ.
    if summary_task is None:
        return SummaryResult()
    try:
        return await summary_task
    except asyncio.CancelledError:
        return SummaryResult(source="skipped")


def _evidence_refs(evidence: Any) -> list[dict[str, Any]]:
    if not isinstance(evidence, list):
        return []
    refs: list[dict[str, Any]] = []
    for item in evidence[:5]:
        if not isinstance(item, dict):
            continue
        resource_type = item.get("resource_type")
        resource_id = item.get("id")
        if not resource_type or not resource_id:
            continue
        refs.append({"resource_type": resource_type, "resource_id": resource_id, "summary": item.get("summary")})
    return refs
