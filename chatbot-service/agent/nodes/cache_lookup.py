"""Semantic cache (câu hỏi → câu trả lời) đặt trước router: hit ⇒ 0 LLM call."""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.answer.finalize import NO_PATIENT_CACHE_KEY, TurnMeta, build_memory_update
from agent.state import RESPONSE_STATUS_ANSWERED, ROUTE_CACHE, AgentState, get_deps
from api.schemas import ChatRequest
from llm.pricing import provider_and_pricing_model
from llm.usage import zero_usage


log = logging.getLogger(__name__)

CACHE_TOOL_NAME = "cache_hit"


async def cache_lookup(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = get_deps(config)
    request: ChatRequest = state["request"]
    patient_id = state.get("provided_patient_id")
    try:
        hit = await deps.cache_service.get_cached_answer(
            user_id=request.user_id,
            patient_id=patient_id or NO_PATIENT_CACHE_KEY,
            question=request.message,
        )
    except Exception:
        log.exception("Semantic cache lookup lỗi; coi như miss")
        return {"cache_hit": False}
    if not hit:
        return {"cache_hit": False}

    # Mục tiêu của hit là ~0 chi phí: rolling summary của lượt này cũng không cần chạy.
    deps.cancel_summary()
    answer, intent, original_usage, provider, model = hit
    settings = deps.cache_service.settings
    if not provider or not model:
        # Entry cũ chưa lưu model gốc → tính saved_cost theo model mặc định.
        provider, model = provider_and_pricing_model(settings.model_simple)

    payload: dict[str, Any] = {
        "answer": answer,
        "intent": intent,
        # patient_id thật (có thể None) chứ không phải sentinel "__no_patient__".
        "patient_id": patient_id,
        "answer_source": "semantic_cache_strict",
        "evidence": [],
        "usage": zero_usage(),
        "saved_usage": {
            "saved_input_tokens": original_usage.get("input_tokens", 0),
            "saved_output_tokens": original_usage.get("output_tokens", 0),
        },
        "tool_name": CACHE_TOOL_NAME,
        "intent_source": "strict_cache",
        "llm_provider": provider,
        "llm_model": model,
        "response_status": RESPONSE_STATUS_ANSWERED,
        "agent_route": ROUTE_CACHE,
    }
    memory_update = build_memory_update(payload, TurnMeta(tool_name=CACHE_TOOL_NAME, intent=intent or "unknown"))
    if memory_update:
        payload["memory_update"] = memory_update
    return {"cache_hit": True, "payload": payload, "response_status": RESPONSE_STATUS_ANSWERED}
