from typing import Any

from agents.intent_extractor import IntentPlan
from api.chat_schemas import ChatRequest
from chat.context_memory import _patient_id_hint
from chat.response_builder import NO_PATIENT_CACHE_KEY, _build_memory_update, _zero_usage
from services.semantic_cache import SemanticCacheService


async def get_cached_chat_payload(
    request: ChatRequest,
    cache_service: SemanticCacheService,
) -> dict[str, Any] | None:
    patient_hint = _patient_id_hint(request)
    patient_id_for_cache = patient_hint or NO_PATIENT_CACHE_KEY
    strict_cache_result = await cache_service.get_cached_answer(
        user_id=request.user_id,
        patient_id=patient_id_for_cache,
        question=request.message,
    )
    if not strict_cache_result:
        return None

    cached_answer, cached_intent, original_usage = strict_cache_result
    payload = {
        "answer": cached_answer,
        "intent": cached_intent,
        "patient_id": patient_id_for_cache,
        "answer_source": "semantic_cache_strict",
        "evidence": [],
        "usage": _zero_usage(),
        "saved_usage": {
            "saved_input_tokens": original_usage.get("input_tokens", 0),
            "saved_output_tokens": original_usage.get("output_tokens", 0),
        },
        "tool_name": "cache_hit",
        "intent_source": "strict_cache",
        "llm_provider": cache_service.settings.llm_provider,
        "llm_model": cache_service.settings.model_simple,
    }
    mock_plan = IntentPlan(
        tool_name="cache_hit",
        patient_id=patient_id_for_cache,
        source="strict_cache",
    )
    memory_update = _build_memory_update(payload, mock_plan)
    if memory_update:
        payload["memory_update"] = memory_update
    return payload
