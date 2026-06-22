import contextvars
from typing import Any

from agents.answer_generator import AnswerGenerator, combine_usage
from agents.intent_extractor import IntentPlan, has_patient_search_criteria
from app.config import get_settings
from services.semantic_cache import get_semantic_cache


current_user_context = contextvars.ContextVar(
    "current_user_context",
    default="",
)

NO_PATIENT_CACHE_KEY = "__no_patient__"


def _zero_usage() -> dict[str, int | float]:
    return {
        "input_tokens": 0,
        "output_tokens": 0,
        "estimated_cost_usd": 0,
    }

async def _finalize_chat_response(
    payload: dict[str, Any],
    question: str,
    plan: IntentPlan,
    answer_generator: AnswerGenerator,
    *,
    model: str | None = None,
    query_complexity: str | None = None,
) -> dict[str, Any]:
    payload = _with_plan_metadata(payload, plan)
    if payload.get("needs_patient_selection"):
        payload["answer_source"] = "template_patient_selection"
        payload["answer_usage"] = _zero_usage()
        payload["usage"] = combine_usage(plan.usage)
        payload.setdefault("patient_id", None)
        payload["pending_question"] = question
        return payload

    template_answer = payload.get("answer") or ""
    answer_result = await answer_generator.generate(
        question=question,
        intent=payload.get("intent") or plan.intent,
        tool_name=plan.tool_name,
        patient_id=payload.get("patient_id") or plan.patient_id,
        evidence=payload.get("evidence") or [],
        fallback_answer=template_answer,
        model=model,
    )
    payload["answer"] = answer_result.answer
    payload["answer_source"] = answer_result.source
    payload["answer_usage"] = answer_result.usage
    if model:
        payload["llm_model"] = model
    if query_complexity:
        payload["query_complexity"] = query_complexity

    if answer_result.source == "llm":
        try:
            cache_svc = get_semantic_cache()
            patient_id_for_cache = payload.get("patient_id") or plan.patient_id or NO_PATIENT_CACHE_KEY

            total_usage = combine_usage(
                plan.usage,
                answer_result.usage
            )

            await cache_svc.save_to_cache(
                user_id=current_user_context.get(),
                patient_id=patient_id_for_cache,
                intent=plan.intent,
                question=question,
                answer=answer_result.answer,
                usage=total_usage
            )
        except Exception as e:
            print(f"Cache save error: {e}")

    if answer_result.reason:
        payload["answer_reason"] = answer_result.reason
    payload["usage"] = combine_usage(plan.usage, answer_result.usage)
    memory_update = _build_memory_update(payload, plan)
    if memory_update:
        payload["memory_update"] = memory_update
    return payload

def _build_memory_update(payload: dict[str, Any], plan: IntentPlan) -> dict[str, Any] | None:
    if payload.get("needs_patient_selection"):
        return None

    evidence = payload.get("evidence") or []
    evidence_refs = _evidence_refs(evidence)
    patient_id = payload.get("patient_id")
    all_patients = bool(payload.get("all_patients"))

    if all_patients and not evidence_refs:
        return {
            "last_intent": payload.get("intent") or plan.intent,
            "last_tool_name": plan.tool_name,
            "summary": _memory_summary(payload, patient_id=None, evidence_refs=[]),
            "evidence_refs": [],
        }

    if not patient_id and not evidence_refs:
        return None

    first_ref = evidence_refs[0] if evidence_refs else {}
    return {
        "active_patient_id": patient_id if not all_patients else None,
        "last_intent": payload.get("intent") or plan.intent,
        "last_tool_name": plan.tool_name,
        "last_resource_type": first_ref.get("resource_type"),
        "last_resource_id": first_ref.get("resource_id"),
        "summary": _memory_summary(payload, patient_id=patient_id, evidence_refs=evidence_refs),
        "evidence_refs": evidence_refs,
    }

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
        refs.append(
            {
                "resource_type": resource_type,
                "resource_id": resource_id,
                "summary": item.get("summary"),
            }
        )
    return refs

def _memory_summary(
    payload: dict[str, Any],
    *,
    patient_id: Any,
    evidence_refs: list[dict[str, Any]],
) -> str:
    intent = payload.get("intent") or "unknown"
    if patient_id and evidence_refs:
        first = evidence_refs[0]
        return (
            f"Da xem {first.get('resource_type')}/{first.get('resource_id')} "
            f"cho Patient/{patient_id}: {first.get('summary') or intent}."
        )
    if patient_id:
        return f"Dang trao doi ve Patient/{patient_id}, intent gan nhat la {intent}."
    if evidence_refs:
        first = evidence_refs[0]
        return f"Da xem {first.get('resource_type')}/{first.get('resource_id')}: {first.get('summary') or intent}."
    return f"Intent gan nhat la {intent}."

def _with_plan_metadata(payload: dict[str, Any], plan: IntentPlan) -> dict[str, Any]:
    settings = get_settings()
    payload["usage"] = plan.usage
    payload["tool_name"] = plan.tool_name
    payload["intent_source"] = plan.source
    payload["confidence_score"] = getattr(plan, "confidence_score", 1.0)
    payload["llm_provider"] = settings.llm_provider
    payload["llm_model"] = settings.model_simple
    if plan.all_patients:
        payload["all_patients"] = True
    if plan.observation_type:
        payload["observation_type"] = plan.observation_type
    if has_patient_search_criteria(plan):
        payload["patient_search"] = {
            "name": plan.search_name,
            "phone": plan.search_phone,
            "birth_date": plan.search_birth_date,
            "identifier": plan.search_identifier,
        }
    return payload
