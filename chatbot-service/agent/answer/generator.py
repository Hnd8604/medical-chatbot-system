"""Sinh câu trả lời tiếng Việt từ evidence đã normalize.

LLM chỉ được tóm tắt evidence (và external_knowledge nếu có). Mọi lỗi LLM đều rơi về câu
template của tool — không bao giờ để lượt chat hỏng vì answer LLM. Riêng lỗi vượt budget
nổi lên để route trả 429.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Protocol

from agent.prompts import ANSWER_SYSTEM_PROMPT
from agent.state import STAGE_ANSWER
from app.config import get_settings
from llm.client import LlmCallError, call_llm
from llm.usage import zero_usage


MAX_EVIDENCE_ITEMS = 20
MAX_EVIDENCE_JSON_CHARS = 12000
MAX_EXTERNAL_KNOWLEDGE_ITEMS = 8
MAX_EXTERNAL_SUMMARY_CHARS = 600

# Trường evidence.data đưa vào prompt; trường ngoài danh sách bị bỏ để tiết kiệm token.
_PROMPT_DATA_KEYS = (
    "resource_type", "id", "active", "status", "intent", "identifier", "class", "type",
    "service_type", "priority", "name", "names", "gender", "birth_date", "phone", "email",
    "telecom", "address", "contact", "subject", "subject_detail", "patient", "encounter",
    "encounter_detail", "participant", "period", "diagnosis", "location", "service_provider",
    "effective_time", "issued", "authored_on", "recorded_date", "onset", "abatement", "code",
    "code_detail", "category", "clinical_status", "verification_status", "severity",
    "medication", "medication_detail", "value", "components", "interpretation",
    "reference_range", "body_site", "method", "performer", "requester", "reason_code",
    "reason_reference", "dosage", "dosage_instruction", "dispense_request", "note",
)


@dataclass(frozen=True)
class AnswerResult:
    answer: str
    source: str = "template"
    usage: dict[str, int | float] = field(default_factory=zero_usage)
    reason: str | None = None
    model: str | None = None


class AnswerGenerator(Protocol):
    async def generate(
        self,
        *,
        question: str,
        intent: str,
        tool_name: str,
        patient_id: str | None,
        evidence: list[dict[str, Any]],
        fallback_answer: str,
        model: str | None = None,
        conversation_context: dict[str, Any] | None = None,
        external_knowledge: list[dict[str, Any]] | None = None,
    ) -> AnswerResult:
        ...


class TemplateAnswerGenerator:
    """Trả thẳng câu template của tool. Dùng khi tắt answer LLM hoặc ở template fast-path."""

    def __init__(self, source: str = "template") -> None:
        self.source = source

    async def generate(self, *, fallback_answer: str, **kwargs: Any) -> AnswerResult:
        return AnswerResult(answer=fallback_answer, source=self.source)


class LlmAnswerGenerator:
    def __init__(self, model: str) -> None:
        self.model = model

    async def generate(
        self,
        *,
        question: str,
        intent: str,
        tool_name: str,
        patient_id: str | None,
        evidence: list[dict[str, Any]],
        fallback_answer: str,
        model: str | None = None,
        conversation_context: dict[str, Any] | None = None,
        external_knowledge: list[dict[str, Any]] | None = None,
    ) -> AnswerResult:
        compact_evidence = compact_evidence_for_llm(evidence)
        compact_knowledge = compact_external_knowledge_for_llm(external_knowledge)
        if not compact_evidence and not compact_knowledge:
            return AnswerResult(
                answer=fallback_answer,
                source="template_no_evidence",
                reason="No evidence was available for LLM answer generation.",
            )

        user_payload: dict[str, Any] = {
            "question": question,
            "intent": intent,
            "tool_name": tool_name,
            "patient_id": patient_id,
            "evidence": compact_evidence,
            "fallback_answer": fallback_answer,
        }
        if compact_knowledge:
            user_payload["external_knowledge"] = compact_knowledge
        if conversation_context:
            user_payload["conversation"] = conversation_context

        try:
            result = await call_llm(
                stage=STAGE_ANSWER,
                model=model or self.model,
                system_prompt=ANSWER_SYSTEM_PROMPT,
                user_payload=user_payload,
                temperature=0.2,
            )
        except LlmCallError as exc:
            return AnswerResult(answer=fallback_answer, source="template_fallback", reason=exc.detail)

        answer = clean_llm_answer(result.content)
        if not answer:
            return AnswerResult(
                answer=fallback_answer,
                source="template_fallback",
                reason="LLM returned an empty answer.",
            )
        return AnswerResult(answer=answer, source="llm", usage=result.usage, model=result.model)


def get_answer_generator() -> AnswerGenerator:
    settings = get_settings()
    if settings.use_llm_answer:
        return LlmAnswerGenerator(model=settings.model_simple)
    return TemplateAnswerGenerator()


def compact_external_knowledge_for_llm(items: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    """Cắt terminology enrichment cho prompt (giới hạn số mục + độ dài summary)."""
    compact: list[dict[str, Any]] = []
    for item in (items or [])[:MAX_EXTERNAL_KNOWLEDGE_ITEMS]:
        if not isinstance(item, dict):
            continue
        summary = item.get("summary")
        if isinstance(summary, str) and len(summary) > MAX_EXTERNAL_SUMMARY_CHARS:
            summary = summary[:MAX_EXTERNAL_SUMMARY_CHARS] + "…"
        compact.append(
            {
                "source": item.get("source"),
                "type": item.get("type"),
                "code": item.get("code"),
                "display": item.get("display"),
                "summary": summary,
                "url": item.get("url"),
            }
        )
    return compact


def compact_evidence_for_llm(evidence: list[dict[str, Any]]) -> list[dict[str, Any]]:
    compact_items = [
        {
            "resource_type": item.get("resource_type"),
            "id": item.get("id"),
            "summary": item.get("summary"),
            "data": compact_resource_data(item.get("data")),
        }
        for item in evidence[:MAX_EVIDENCE_ITEMS]
    ]
    while compact_items and len(json.dumps(compact_items, ensure_ascii=False)) > MAX_EVIDENCE_JSON_CHARS:
        compact_items.pop()
    return compact_items


def compact_resource_data(data: Any) -> Any:
    if not isinstance(data, dict):
        return data
    return {
        key: data[key]
        for key in _PROMPT_DATA_KEYS
        if key in data and data[key] not in (None, [], {})
    }


def clean_llm_answer(answer: str) -> str:
    cleaned = answer.strip()
    cleaned = re.sub(r"\*\*([^*]+)\*\*", r"\1", cleaned)
    cleaned = re.sub(r"__([^_]+)__", r"\1", cleaned)
    cleaned = re.sub(r"\*([^*\n]+)\*", r"\1", cleaned)
    cleaned = re.sub(r"`([^`]+)`", r"\1", cleaned)
    return cleaned
