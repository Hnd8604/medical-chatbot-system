import json
import re
from dataclasses import dataclass, field
from typing import Any, Protocol

from app.config import get_settings


MAX_EVIDENCE_ITEMS = 20
MAX_EVIDENCE_JSON_CHARS = 12000

ZERO_USAGE = {
    "input_tokens": 0,
    "output_tokens": 0,
    "estimated_cost_usd": 0,
}


@dataclass(frozen=True)
class AnswerResult:
    answer: str
    source: str = "template"
    usage: dict[str, int | float] = field(default_factory=lambda: dict(ZERO_USAGE))
    reason: str | None = None


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
    ) -> AnswerResult:
        ...


class TemplateAnswerGenerator:
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
    ) -> AnswerResult:
        return AnswerResult(answer=fallback_answer, source="template")


class OpenAIAnswerGenerator:
    def __init__(self, api_key: str, model: str, timeout_seconds: float, base_url: str | None = None) -> None:
        from openai import AsyncOpenAI

        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)
        self.model = model
        self.fallback = TemplateAnswerGenerator()

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
    ) -> AnswerResult:
        compact_evidence = compact_evidence_for_llm(evidence)
        if not compact_evidence:
            return AnswerResult(
                answer=fallback_answer,
                source="template_no_evidence",
                reason="No evidence was available for LLM answer generation.",
            )

        system_prompt = (
            "Bạn là trợ lý y tế cho người Việt. "
            "Bạn chỉ được trả lời dựa trên dữ liệu FHIR evidence do backend cung cấp. "
            "Không tự bịa thông tin bệnh nhân, thuốc, chẩn đoán, chỉ số hoặc ngày giờ. "
            "Không đưa chẩn đoán mới hoặc lời khuyên điều trị chắc chắn. "
            "Nếu dữ liệu thiếu, hãy nói rõ dữ liệu FHIR hiện không có thông tin đó. "
            "Trả lời bằng tiếng Việt, rõ ràng, có cấu trúc ngắn gọn. "
            "Không dùng Markdown đậm/nghiêng; dùng văn bản thường, xuống dòng và danh sách đánh số nếu cần. "
            "Ưu tiên nêu giá trị, đơn vị, thời điểm, diễn giải/reference range nếu có. "
            "Kết thúc bằng một câu nhắc rằng câu trả lời chỉ dựa trên dữ liệu hiện có nếu câu hỏi có tính y khoa."
        )
        user_payload = {
            "question": question,
            "intent": intent,
            "tool_name": tool_name,
            "patient_id": patient_id,
            "evidence": compact_evidence,
            "fallback_answer": fallback_answer,
        }

        try:
            response = await self.client.chat.completions.create(
                model=model or self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
                ],
                temperature=0.2,
            )
        except Exception as exc:
            return AnswerResult(
                answer=fallback_answer,
                source="template_fallback",
                reason=str(exc),
            )

        answer = clean_llm_answer(response.choices[0].message.content or "")
        if not answer:
            return AnswerResult(
                answer=fallback_answer,
                source="template_fallback",
                reason="LLM returned an empty answer.",
            )

        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) if response.usage else 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) if response.usage else 0,
            "estimated_cost_usd": 0,
        }
        return AnswerResult(answer=answer, source="llm", usage=usage)


def get_answer_generator() -> AnswerGenerator:
    settings = get_settings()
    if settings.use_llm_answer and settings.openai_api_key:
        return OpenAIAnswerGenerator(
            api_key=settings.openai_api_key,
            model=settings.model_simple,
            timeout_seconds=settings.llm_request_timeout_seconds,
            base_url=settings.openai_base_url,
        )
    return TemplateAnswerGenerator()


def compact_evidence_for_llm(evidence: list[dict[str, Any]]) -> list[dict[str, Any]]:
    compact_items = []
    for item in evidence[:MAX_EVIDENCE_ITEMS]:
        compact_items.append(
            {
                "resource_type": item.get("resource_type"),
                "id": item.get("id"),
                "summary": item.get("summary"),
                "data": compact_resource_data(item.get("data")),
            }
        )

    while compact_items and len(json.dumps(compact_items, ensure_ascii=False)) > MAX_EVIDENCE_JSON_CHARS:
        compact_items.pop()

    return compact_items


def compact_resource_data(data: Any) -> Any:
    if not isinstance(data, dict):
        return data

    keys = [
        "resource_type",
        "id",
        "active",
        "status",
        "intent",
        "identifier",
        "class",
        "type",
        "service_type",
        "priority",
        "name",
        "names",
        "gender",
        "birth_date",
        "phone",
        "email",
        "telecom",
        "address",
        "contact",
        "subject",
        "subject_detail",
        "patient",
        "encounter",
        "encounter_detail",
        "participant",
        "period",
        "diagnosis",
        "location",
        "service_provider",
        "effective_time",
        "issued",
        "authored_on",
        "recorded_date",
        "onset",
        "abatement",
        "code",
        "code_detail",
        "category",
        "clinical_status",
        "verification_status",
        "severity",
        "medication",
        "medication_detail",
        "value",
        "components",
        "interpretation",
        "reference_range",
        "body_site",
        "method",
        "performer",
        "requester",
        "reason_code",
        "reason_reference",
        "dosage",
        "dosage_instruction",
        "dispense_request",
        "note",
    ]
    return {
        key: data[key]
        for key in keys
        if key in data and data[key] not in (None, [], {})
    }


def combine_usage(*usages: dict[str, Any] | None) -> dict[str, int | float]:
    combined = dict(ZERO_USAGE)
    for usage in usages:
        if not usage:
            continue
        combined["input_tokens"] += _number(usage.get("input_tokens"))
        combined["output_tokens"] += _number(usage.get("output_tokens"))
        combined["estimated_cost_usd"] += _number(usage.get("estimated_cost_usd"))
    return combined


def clean_llm_answer(answer: str) -> str:
    cleaned = answer.strip()
    cleaned = re.sub(r"\*\*([^*]+)\*\*", r"\1", cleaned)
    cleaned = re.sub(r"__([^_]+)__", r"\1", cleaned)
    cleaned = re.sub(r"\*([^*\n]+)\*", r"\1", cleaned)
    cleaned = re.sub(r"`([^`]+)`", r"\1", cleaned)
    return cleaned


def _number(value: Any) -> int | float:
    return value if isinstance(value, (int, float)) else 0
