import asyncio
from dataclasses import replace
from typing import Any
import logging
from fastapi import APIRouter, Depends, HTTPException, status

from agents.answer_generator import AnswerGenerator, get_answer_generator
from agents.context_payload import compact_conversation_for_llm
from agents.model_router import ModelRouter, get_model_router
from agents.summary_generator import SummaryGenerator, get_summary_generator
from agents.intent_extractor import (
    FHIR_PROTECTED_TOOLS,
    TOOL_FHIR_STATUS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
    TOOL_EXPLAIN_CONCEPT,
    IntentExtractor,
    get_intent_extractor,
    is_self_patient_reference,
    normalize_patient_id,
)
from agents.gateway_context import GatewayBudgetExceededError, set_gateway_context
from api.chat_schemas import ChatRequest
from chat.cache_flow import get_cached_chat_payload
from chat.context_memory import _patient_id_hint
from chat.resource_answerers import (
    _answer_all_patient_conditions,
    _answer_all_patient_encounters,
    _answer_all_patient_medications,
    _answer_all_patient_observations,
    _answer_conditions,
    _answer_encounters,
    _answer_fhir_status,
    _answer_medications,
    _answer_explain_concept,
    _answer_observations,
    _answer_patient,
    _answer_patients,
    _answer_resource_by_id,
    _resolve_patient_id_for_tool,
)
from chat.response_builder import _finalize_chat_response, current_user_context
from app.config import get_settings
from fhir.client import FhirClient, FhirClientError, get_fhir_client
from services.semantic_cache import SemanticCacheService, get_semantic_cache


log = logging.getLogger(__name__)

router = APIRouter(tags=["chat"])


# USER không được tìm kiếm bệnh nhân khác và không được tra cứu resource FHIR
# tuỳ ý (không kiểm chứng được resource thuộc về chính họ trước khi truy xuất).
USER_ALLOWED_FHIR_TOOLS = FHIR_PROTECTED_TOOLS - {TOOL_SEARCH_PATIENTS, TOOL_GET_RESOURCE}


def _budget_exceeded_http() -> HTTPException:
    # Gateway chan vi virtual key vuot budget cost/ngay. Spring nhan 429 va chuyen tiep
    # nguyen status/detail xuong frontend (giong quota exceeded).
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail="Đã đạt hạn mức chi phí AI trong ngày. Vui lòng thử lại sau khi hạn mức được đặt lại.",
    )


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

    if plan.tool_name == TOOL_GET_RESOURCE:
        _user_scope_error("Tai khoan USER khong duoc tra cuu resource FHIR truc tiep theo ma resource.")

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
    if plan.tool_name == TOOL_GET_RESOURCE:
        _user_scope_error("Tai khoan USER khong duoc tra cuu resource FHIR truc tiep theo ma resource.")
    if plan.tool_name == TOOL_SEARCH_PATIENTS or plan.all_patients:
        _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")
    patient_id = normalize_patient_id(plan.patient_id)
    if not patient_id or patient_id not in _allowed_patient_ids(request):
        _user_scope_error("Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh.")


@router.post("/chat/langgraph")
async def chat_langgraph(
    request: ChatRequest,
    client: FhirClient = Depends(get_fhir_client),
    intent_extractor: IntentExtractor = Depends(get_intent_extractor),
    answer_generator: AnswerGenerator = Depends(get_answer_generator),
    cache_service: SemanticCacheService = Depends(get_semantic_cache),
    model_router: ModelRouter = Depends(get_model_router),
    summary_generator: SummaryGenerator = Depends(get_summary_generator),
) -> dict[str, Any]:
    """Đường agent (M-LG): router → planner → validator → executor → answer.

    Chạy song song với ``POST /chat`` cho tới khi bộ eval (M-LG6) chứng minh chi phí
    mỗi lượt không tăng. Trả về đúng shape response mà Spring/frontend đang đọc,
    kèm vài trường bổ sung tuỳ chọn (``response_status``, ``plan_steps``,
    ``stage_usage``, ``agent_route``).
    """
    if not get_settings().use_langgraph_agent:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Agent chưa được bật (ENABLE_LANGGRAPH_AGENT=false).",
        )

    from langgraph_agent.errors import AgentError
    from langgraph_agent.graph import run_agent

    try:
        return await run_agent(
            request,
            client=client,
            answer_generator=answer_generator,
            cache_service=cache_service,
            model_router=model_router,
            summary_generator=summary_generator,
            intent_extractor=intent_extractor,
        )
    except GatewayBudgetExceededError as exc:
        raise _budget_exceeded_http() from exc
    except AgentError as exc:
        # PolicyError -> 403, PlanError -> 400. Giữ nguyên mã để Spring chuyển tiếp.
        raise exc.as_http() from exc
    except FhirClientError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=exc.user_message,
        ) from exc


@router.post("/chat")
async def chat(
    request: ChatRequest,
    client: FhirClient = Depends(get_fhir_client),
    intent_extractor: IntentExtractor = Depends(get_intent_extractor),
    answer_generator: AnswerGenerator = Depends(get_answer_generator),
    cache_service: SemanticCacheService = Depends(get_semantic_cache),
    model_router: ModelRouter = Depends(get_model_router),
    summary_generator: SummaryGenerator = Depends(get_summary_generator),
) -> dict[str, Any]:
    current_user_context.set(request.user_id)
    # Virtual key + end-user cho AI Gateway: cac call site LLM doc lai qua contextvar.
    set_gateway_context(request.llm_key, request.user_id)

    try:
        cached_payload = await get_cached_chat_payload(request, cache_service)
        if cached_payload:
            return cached_payload
    except Exception:
        log.exception("Strict Cache read error")

    if not hasattr(model_router, "route"):
        model_router = get_model_router()
    if not hasattr(summary_generator, "summarize"):
        summary_generator = get_summary_generator()

    patient_hint = _patient_id_hint(request)
    conversation_context = compact_conversation_for_llm(request.conversation_context)
    total_message_count = (
        request.conversation_context.total_message_count if request.conversation_context else None
    )

    # Rolling summary (context compression) chạy song song với toàn bộ phần còn lại
    # của request (intent + FHIR + answer); _finalize_chat_response mới await kết quả.
    # Task tự nuốt mọi lỗi (kể cả budget) và luôn trả SummaryResult.
    summary_task = asyncio.create_task(
        summary_generator.summarize(
            conversation_context=conversation_context,
            question=request.message,
            patient_id=patient_hint,
            total_message_count=total_message_count,
        )
    )

    try:
        # Router chỉ cần message + quota nên chạy song song với intent extraction,
        # LLM Router (nếu bật) gần như không cộng thêm latency.
        # return_exceptions=True: cả hai nhánh chạy đến cùng và exception của từng nhánh
        # đều được retrieve — tránh task mồ côi ("Task exception was never retrieved")
        # khi một nhánh raise GatewayBudgetExceededError trước nhánh kia.
        plan, routing = await asyncio.gather(
            intent_extractor.extract(
                request.message,
                provided_patient_id=patient_hint,
                conversation_context=conversation_context,
            ),
            model_router.route(request.message, request.quota_used_ratio),
            return_exceptions=True,
        )
        for result in (plan, routing):
            if isinstance(result, GatewayBudgetExceededError):
                raise _budget_exceeded_http() from result
        if isinstance(plan, BaseException):
            raise plan
        if isinstance(routing, BaseException):
            raise routing
        plan = _apply_user_patient_scope(request, plan)
        _ensure_role_can_access_plan(request, plan)
    except BaseException:
        # Request kết thúc sớm (429/403/lỗi) → không rò task summary chạy nền.
        if not summary_task.done():
            summary_task.cancel()
        raise

    finalize_kwargs = dict(
        model=routing.model,
        query_complexity=routing.complexity.value,
        routing_source=routing.source,
        router_usage=routing.usage,
        summary_task=summary_task,
        conversation_context=conversation_context,
    )

    try:
        if plan.tool_name == TOOL_FHIR_STATUS:
            return await _finalize_chat_response(
                await _answer_fhir_status(client),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_GET_RESOURCE:
            return await _finalize_chat_response(
                await _answer_resource_by_id(client, plan),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_EXPLAIN_CONCEPT:
            # Câu hỏi khái niệm thuần (không gắn bệnh nhân) — chỉ tra terminology.
            return await _finalize_chat_response(
                await _answer_explain_concept(plan),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_SEARCH_PATIENTS:
            return await _finalize_chat_response(
                await _answer_patients(client, plan),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_GET_MEDICATIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_medications(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                    **finalize_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **finalize_kwargs)
            return await _finalize_chat_response(
                await _answer_medications(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_GET_ENCOUNTERS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_encounters(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                    **finalize_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **finalize_kwargs)
            return await _finalize_chat_response(
                await _answer_encounters(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_GET_OBSERVATIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_observations(client, plan.limit, plan.observation_type),
                    request.message,
                    plan,
                    answer_generator,
                    **finalize_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **finalize_kwargs)
            return await _finalize_chat_response(
                await _answer_observations(client, resolved_patient_id, plan.limit, plan.observation_type),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_GET_CONDITIONS:
            if plan.all_patients:
                return await _finalize_chat_response(
                    await _answer_all_patient_conditions(client, plan.limit),
                    request.message,
                    plan,
                    answer_generator,
                    **finalize_kwargs,
                )
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **finalize_kwargs)
            return await _finalize_chat_response(
                await _answer_conditions(client, resolved_patient_id, plan.limit),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
        if plan.tool_name == TOOL_GET_PATIENT:
            resolved_patient_id = await _resolve_patient_id_for_tool(client, plan)
            if isinstance(resolved_patient_id, dict):
                return await _finalize_chat_response(resolved_patient_id, request.message, plan, answer_generator, **finalize_kwargs)
            return await _finalize_chat_response(
                await _answer_patient(client, resolved_patient_id),
                request.message,
                plan,
                answer_generator,
                **finalize_kwargs,
            )
    except GatewayBudgetExceededError as exc:
        summary_task.cancel()  # no-op nếu task đã xong
        raise _budget_exceeded_http() from exc
    except FhirClientError as exc:
        summary_task.cancel()
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=exc.user_message,
        ) from exc
    except BaseException:
        summary_task.cancel()
        raise

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
    return await _finalize_chat_response(payload, request.message, plan, answer_generator, **finalize_kwargs)
