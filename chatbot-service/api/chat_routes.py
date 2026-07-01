from dataclasses import replace
from typing import Any
import logging
from fastapi import APIRouter, Depends, HTTPException, status

from agents.answer_generator import AnswerGenerator, get_answer_generator
from agents.model_router import ModelRouter, get_model_router
from agents.intent_extractor import (
    FHIR_PROTECTED_TOOLS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    IntentExtractor,
    get_intent_extractor,
    is_self_patient_reference,
    normalize_patient_id,
)
from api.chat_schemas import ChatRequest
from chat.cache_flow import get_cached_chat_payload
from chat.context_memory import (
    _answer_context_resource_if_applicable,
    _apply_context_reference_context,
    _apply_selected_patient_context,
    _patient_id_hint,
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
    _resolve_patient_id_for_tool,
)
from chat.response_builder import _finalize_chat_response, current_user_context
from fhir.client import FhirClient, FhirClientError, get_fhir_client
from services.semantic_cache import SemanticCacheService, get_semantic_cache


log = logging.getLogger(__name__)

router = APIRouter(tags=["chat"])


USER_ALLOWED_FHIR_TOOLS = FHIR_PROTECTED_TOOLS - {TOOL_SEARCH_PATIENTS}


def _allowed_patient_ids(request: ChatRequest) -> list[str]:
    normalized = []
    for patient_id in request.allowed_patient_ids:
        value = normalize_patient_id(patient_id)
        if value and value not in normalized:
            normalized.append(value)
    return normalized


def _user_scope_error(detail: str) -> None:
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=detail,
    )


def _apply_user_patient_scope(request: ChatRequest, plan):
    if request.user_role != "USER":
        return plan

    allowed_patient_ids = _allowed_patient_ids(request)
    if not allowed_patient_ids:
        _user_scope_error("Tai khoan USER chua duoc lien ket voi ho so FHIR nao.")

    if plan.all_patients:
        _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")

    if is_self_patient_reference(request.message) and plan.tool_name == TOOL_SEARCH_PATIENTS:
        scoped_patient_id = _patient_id_hint(request) or allowed_patient_ids[0]
        if scoped_patient_id not in allowed_patient_ids:
            _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")
        return replace(
            plan,
            tool_name=TOOL_GET_PATIENT,
            patient_id=scoped_patient_id,
            search_name=None,
            search_phone=None,
            search_birth_date=None,
            search_identifier=None,
            all_patients=False,
            source=f"{plan.source}_user_scope",
        )

    if plan.tool_name == TOOL_SEARCH_PATIENTS:
        _user_scope_error("Tai khoan USER khong duoc tim kiem danh sach benh nhan.")

    if plan.tool_name not in USER_ALLOWED_FHIR_TOOLS:
        return plan

    patient_id = normalize_patient_id(plan.patient_id) or _patient_id_hint(request) or allowed_patient_ids[0]
    if patient_id not in allowed_patient_ids:
        _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")

    return replace(
        plan,
        patient_id=patient_id,
        search_name=None,
        search_phone=None,
        search_birth_date=None,
        search_identifier=None,
        all_patients=False,
        source=f"{plan.source}_user_scope",
    )


def _ensure_role_can_access_plan(request: ChatRequest, plan) -> None:
    if request.user_role != "USER":
        return
    if plan.tool_name not in FHIR_PROTECTED_TOOLS:
        return
    if plan.tool_name == TOOL_SEARCH_PATIENTS or plan.all_patients:
        _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")
    patient_id = normalize_patient_id(plan.patient_id)
    if not patient_id or patient_id not in _allowed_patient_ids(request):
        _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")


@router.post("/chat")
async def chat(
    request: ChatRequest,
    client: FhirClient = Depends(get_fhir_client),
    intent_extractor: IntentExtractor = Depends(get_intent_extractor),
    answer_generator: AnswerGenerator = Depends(get_answer_generator),
    cache_service: SemanticCacheService = Depends(get_semantic_cache),
    model_router: ModelRouter = Depends(get_model_router),
) -> dict[str, Any]:
    current_user_context.set(request.user_id)

    try:
        cached_payload = await get_cached_chat_payload(request, cache_service)
        if cached_payload:
            return cached_payload
    except Exception:
        log.exception("Strict Cache read error")

    patient_hint = _patient_id_hint(request)
    plan = await intent_extractor.extract(
        request.message,
        provided_patient_id=patient_hint,
    )
    plan = _apply_user_patient_scope(request, plan)
    plan = _apply_selected_patient_context(request, plan)
    plan = _apply_context_reference_context(request, plan)
    plan = _apply_user_patient_scope(request, plan)
    _ensure_role_can_access_plan(request, plan)
    if not hasattr(model_router, "route"):
        model_router = get_model_router()
    routed_model, complexity = model_router.route(request.message, request.quota_used_ratio)

    routing_kwargs = dict(model=routed_model, query_complexity=complexity.value)

    try:
        context_payload = await _answer_context_resource_if_applicable(client, request, plan)
        if context_payload:
            return await _finalize_chat_response(
                context_payload,
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
            )

        if plan.tool_name == TOOL_SEARCH_PATIENTS:
            return await _finalize_chat_response(
                await _answer_patients(client, plan),
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
            )
        if plan.tool_name == TOOL_GET_MEDICATIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_medications(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                    **routing_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **routing_kwargs)
            return await _finalize_chat_response(
                await _answer_medications(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
            )
        if plan.tool_name == TOOL_GET_ENCOUNTERS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_encounters(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                    **routing_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **routing_kwargs)
            return await _finalize_chat_response(
                await _answer_encounters(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
            )
        if plan.tool_name == TOOL_GET_OBSERVATIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_observations(client, plan.limit, plan.observation_type),
                    request.message,
                    plan,
                    answer_generator,
                    **routing_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **routing_kwargs)
            return await _finalize_chat_response(
                await _answer_observations(client, resolved_patient_id, plan.limit, plan.observation_type),
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
            )
        if plan.tool_name == TOOL_GET_CONDITIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_conditions(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                    **routing_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **routing_kwargs)
            return await _finalize_chat_response(
                await _answer_conditions(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
            )
        if plan.tool_name == TOOL_GET_PATIENT:
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **routing_kwargs)
            return await _finalize_chat_response(
                await _answer_patient(client, resolved_patient_id),
                request.message,
                plan,
                answer_generator,
                **routing_kwargs,
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
    return await _finalize_chat_response(payload, request.message, plan, answer_generator, **routing_kwargs)
