"""Node đầu tiên: chuẩn hoá đầu vào của lượt, không gọi LLM hay I/O."""

from __future__ import annotations

from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.memory.context import compact_conversation_for_llm, patient_id_hint
from agent.state import AgentState
from api.schemas import ChatRequest
from app.config import get_settings
from nlp.patient_refs import resolve_explicit_patient_id
from nlp.text import normalize_patient_id


async def prepare(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    request: ChatRequest = state["request"]

    allowed: list[str] = []
    for patient_id in request.allowed_patient_ids:
        value = normalize_patient_id(patient_id)
        if value and value not in allowed:
            allowed.append(value)

    provided = (
        normalize_patient_id(request.patient_id)
        or resolve_explicit_patient_id(request.message)
        or patient_id_hint(request)
        or (allowed[0] if request.user_role == "USER" and allowed else None)
    )
    return {
        "allowed_patient_ids": allowed,
        "provided_patient_id": provided,
        "conversation_context": compact_conversation_for_llm(request.conversation_context),
        "low_cost_mode": get_settings().is_low_cost(request.quota_used_ratio),
    }
