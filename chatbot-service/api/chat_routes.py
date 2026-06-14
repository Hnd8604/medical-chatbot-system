from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from agents.answer_generator import AnswerGenerator, get_answer_generator
from agents.intent_extractor import (
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    IntentExtractor,
    get_intent_extractor,
)
from api.chat_schemas import ChatRequest, ConversationContext, RecentMessage
from chat.cache_flow import get_cached_chat_payload
from chat.context_memory import (
    _answer_context_resource_if_applicable,
    _apply_context_reference_context,
    _apply_selected_patient_context,
    _canonical_resource_type,
    _detect_intent,
    _is_context_reference_question,
    _normalize_single_resource,
    _patient_id_from_resource,
    _patient_id_hint,
    _resolve_patient_id,
    _tool_for_resource_type,
)
from chat.formatters import (
    _encounter_location_text,
    _first_text,
    _format_all_patient_answer,
    _format_encounter,
    _format_observation,
    _format_patient_identity,
    _format_patient_search_criteria,
    _format_patient_summary,
    _format_resource_summary,
)
from chat.resource_answerers import (
    _answer_all_patient_conditions,
    _answer_all_patient_encounters,
    _answer_all_patient_medications,
    _answer_all_patient_observations,
    _answer_conditions,
    _answer_encounters,
    _answer_medications,
    _answer_observations,
    _answer_patient,
    _answer_patients,
    _evidence,
    _get_patients,
    _observation_matches_type,
    _patient_candidate,
    _patient_selection_payload,
    _resolve_patient_id_for_tool,
    _search_patients_for_plan,
    _with_patient_context,
)
from chat.response_builder import (
    _build_memory_update,
    _evidence_refs,
    _finalize_chat_response,
    _memory_summary,
    _with_plan_metadata,
    _zero_usage,
    current_user_context,
)
from chat.text_helpers import _contains_any, _display_vi, _gender_vi, _value_or_unknown
from fhir.client import FhirClient, FhirClientError, get_fhir_client
from services.semantic_cache import SemanticCacheService, get_semantic_cache


router = APIRouter(tags=["chat"])


@router.post("/chat")
async def chat(
    request: ChatRequest,
    client: FhirClient = Depends(get_fhir_client),
    intent_extractor: IntentExtractor = Depends(get_intent_extractor),
    answer_generator: AnswerGenerator = Depends(get_answer_generator),
    cache_service: SemanticCacheService = Depends(get_semantic_cache),
) -> dict[str, Any]:
    current_user_context.set(request.user_id)

    try:
        cached_payload = await get_cached_chat_payload(request, cache_service)
        if cached_payload:
            return cached_payload
    except Exception as exc:
        print(f"Strict Cache read error: {exc}")

    patient_hint = _patient_id_hint(request)
    plan = await intent_extractor.extract(
        request.message,
        provided_patient_id=patient_hint,
    )
    plan = _apply_selected_patient_context(request, plan)
    plan = _apply_context_reference_context(request, plan)

    try:
        context_payload = await _answer_context_resource_if_applicable(client, request, plan)
        if context_payload:
            return await _finalize_chat_response(
                context_payload,
                request.message,
                plan,
                answer_generator,
            )

        if plan.tool_name == TOOL_SEARCH_PATIENTS:
            return await _finalize_chat_response(
                await _answer_patients(client, plan),
                request.message,
                plan,
                answer_generator,
            )
        if plan.tool_name == TOOL_GET_MEDICATIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_medications(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator)
            return await _finalize_chat_response(
                await _answer_medications(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
            )
        if plan.tool_name == TOOL_GET_ENCOUNTERS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_encounters(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator)
            return await _finalize_chat_response(
                await _answer_encounters(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
            )
        if plan.tool_name == TOOL_GET_OBSERVATIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_observations(client, plan.limit, plan.observation_type),
                    request.message,
                    plan,
                    answer_generator,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator)
            return await _finalize_chat_response(
                await _answer_observations(client, resolved_patient_id, plan.limit, plan.observation_type),
                request.message,
                plan,
                answer_generator,
            )
        if plan.tool_name == TOOL_GET_CONDITIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_conditions(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator)
            return await _finalize_chat_response(
                await _answer_conditions(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
            )
        if plan.tool_name == TOOL_GET_PATIENT:
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator)
            return await _finalize_chat_response(
                await _answer_patient(client, resolved_patient_id),
                request.message,
                plan,
                answer_generator,
            )
    except FhirClientError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=exc.user_message,
        ) from exc

    payload = {
        "answer": (
            "Tôi chưa xác định được cần lấy loại dữ liệu FHIR nào. "
            "Bạn có thể hỏi về thông tin bệnh nhân, chỉ số/xét nghiệm, chẩn đoán hoặc thuốc, "
            "và nên kèm mã bệnh nhân nếu có."
        ),
        "intent": "unknown",
        "patient_id": plan.patient_id,
        "evidence": [],
        "usage": plan.usage,
        "tool_name": plan.tool_name,
        "intent_source": plan.source,
        "intent_reason": plan.reason,
    }
    return await _finalize_chat_response(payload, request.message, plan, answer_generator)
