"""Chạy plan đã validate và gộp kết quả thành một payload.

Hai điểm khác bản tham chiếu:

1. **Các step độc lập chạy song song** (``asyncio.gather``). Bản tham chiếu chạy tuần tự
   kể cả khi 3 step cùng trỏ về một bệnh nhân, nên latency cộng dồn vô ích.
2. **Không dựng lại payload từ đầu.** Tool đã trả về payload chuẩn (kèm câu trả lời
   template tiếng Việt), nên executor chỉ ghép — nhờ vậy đường agent và đường ``/chat``
   không thể lệch nhau về cách format dữ liệu.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any

from agents.intent.constants import TOOL_GET_RESOURCE, TOOL_SEARCH_PATIENTS
from agents.intent.models import IntentPlan
from agents.intent.patient_utils import normalize_patient_id
from app.config import get_settings
from chat.response_builder import _zero_usage
from fhir.client import FhirClient, FhirClientError
from fhir.tool_registry import PATIENT_SCOPED_TOOLS, get_tool
from langgraph_agent.errors import PolicyError
from langgraph_agent.planner import Plan, PlanStep
from langgraph_agent.plan_validator import STEP_REF_PREFIX
from langgraph_agent.state import (
    RESPONSE_STATUS_ANSWERED,
    RESPONSE_STATUS_CLARIFICATION,
    RESPONSE_STATUS_NO_DATA,
    RESPONSE_STATUS_PATIENT_SELECTION,
    RESPONSE_STATUS_TOOL_ERROR,
)


log = logging.getLogger(__name__)

MULTI_TOOL_NAME = "multi_tool_plan"
PLAN_SOURCE_PREFIX = "langgraph"

CLARIFICATION_ANSWER = (
    "Mình chưa xác định được bệnh nhân cụ thể. "
    "Hãy chọn bệnh nhân trên giao diện hoặc cung cấp mã bệnh nhân, tên, "
    "số điện thoại, ngày sinh hay mã định danh."
)


@dataclass
class ExecutionResult:
    payload: dict[str, Any]
    plan: IntentPlan
    response_status: str
    resolved_patient_id: str | None = None
    executed_steps: list[dict[str, Any]] = field(default_factory=list)


async def execute_plan(
    plan: Plan,
    *,
    client: FhirClient,
    message: str,
    user_role: str,
    allowed_patient_ids: list[str],
    provided_patient_id: str | None,
    plan_usage: dict[str, int | float],
) -> ExecutionResult:
    resolved_patient_id = normalize_patient_id(provided_patient_id)
    executed: list[dict[str, Any]] = []

    search_steps = [step for step in plan.steps if step.tool == TOOL_SEARCH_PATIENTS]
    other_steps = [step for step in plan.steps if step.tool != TOOL_SEARCH_PATIENTS]

    # --- Bước 1: chốt bệnh nhân trước, vì các step còn lại có thể phụ thuộc vào nó.
    for step in search_steps:
        outcome = await _run_step(client, step, step.args)
        executed.append({"id": step.id, "tool": step.tool, "args": step.args})
        if outcome.status is not None:
            return _single_result(plan, outcome, plan_usage, executed, resolved_patient_id)

        payload = outcome.payload
        if payload.get("needs_patient_selection"):
            # Nhiều bệnh nhân khớp: dừng lại hỏi người dùng thay vì đoán.
            return _single_result(
                plan,
                _Outcome(payload, RESPONSE_STATUS_PATIENT_SELECTION),
                plan_usage,
                executed,
                resolved_patient_id,
            )
        found = normalize_patient_id(payload.get("patient_id"))
        if found:
            resolved_patient_id = found
        elif not other_steps:
            # search_patients là step cuối và trả danh sách: đó chính là câu trả lời.
            return _single_result(
                plan,
                _Outcome(payload, _status_for_payload(payload)),
                plan_usage,
                executed,
                resolved_patient_id,
            )
        else:
            return _single_result(
                plan,
                _Outcome(_clarification_payload(), RESPONSE_STATUS_CLARIFICATION),
                plan_usage,
                executed,
                resolved_patient_id,
            )

    # --- Bước 2: bơm patient_id đã chốt vào các step còn thiếu.
    prepared: list[tuple[PlanStep, dict[str, Any]]] = []
    for step in other_steps:
        args = _fill_patient_id(step.args, resolved_patient_id)
        if step.tool in PATIENT_SCOPED_TOOLS and not args.get("patient_id"):
            return _single_result(
                plan,
                _Outcome(_clarification_payload(), RESPONSE_STATUS_CLARIFICATION),
                plan_usage,
                executed,
                resolved_patient_id,
            )
        if step.tool in PATIENT_SCOPED_TOOLS:
            _assert_patient_allowed(args["patient_id"], user_role, allowed_patient_ids)
        prepared.append((step, args))

    if not prepared:
        return _single_result(
            plan,
            _Outcome(_clarification_payload(), RESPONSE_STATUS_CLARIFICATION),
            plan_usage,
            executed,
            resolved_patient_id,
        )

    # --- Bước 3: các step còn lại độc lập với nhau -> chạy song song.
    outcomes = await asyncio.gather(
        *(_run_step(client, step, args) for step, args in prepared)
    )
    for (step, args), outcome in zip(prepared, outcomes):
        executed.append({"id": step.id, "tool": step.tool, "args": args})
        if outcome.status is not None:
            return _single_result(plan, outcome, plan_usage, executed, resolved_patient_id)

    payloads = [outcome.payload for outcome in outcomes]
    for (step, args), payload in zip(prepared, payloads):
        if step.tool == TOOL_GET_RESOURCE:
            _enforce_resource_ownership(payload, user_role, allowed_patient_ids)

    if len(payloads) == 1:
        merged = payloads[0]
        tool_names = [prepared[0][0].tool]
    else:
        merged = _merge_payloads(payloads)
        tool_names = [step.tool for step, _ in prepared]

    resolved_patient_id = normalize_patient_id(merged.get("patient_id")) or resolved_patient_id
    return ExecutionResult(
        payload=merged,
        plan=_build_intent_plan(
            tool_names=tool_names,
            args_list=[args for _, args in prepared],
            payload=merged,
            plan=plan,
            patient_id=resolved_patient_id,
            usage=plan_usage,
        ),
        response_status=_status_for_payload(merged),
        resolved_patient_id=resolved_patient_id,
        executed_steps=executed,
    )


@dataclass
class _Outcome:
    payload: dict[str, Any]
    status: str | None = None


async def _run_step(client: FhirClient, step: PlanStep, args: dict[str, Any]) -> _Outcome:
    tool = get_tool(step.tool)
    if tool is None:  # đã chặn ở validator; giữ để phòng thủ chiều sâu
        return _Outcome(_tool_error_payload(f"Không tìm thấy tool {step.tool}."), RESPONSE_STATUS_TOOL_ERROR)
    try:
        return _Outcome(await tool.run(client, args))
    except FhirClientError as exc:
        # Trả payload thay vì 502: người dùng nhận được câu trả lời giải thích sự cố,
        # và response_status=tool_error cho phép Spring/FE phân biệt với câu trả lời thật.
        log.warning("FHIR tool %s lỗi: %s", step.tool, exc)
        return _Outcome(_tool_error_payload(exc.user_message), RESPONSE_STATUS_TOOL_ERROR)


def _fill_patient_id(args: dict[str, Any], resolved_patient_id: str | None) -> dict[str, Any]:
    args = dict(args)
    raw = args.get("patient_id")
    if isinstance(raw, str) and raw.startswith(STEP_REF_PREFIX):
        args.pop("patient_id", None)
    if not args.get("patient_id") and resolved_patient_id:
        args["patient_id"] = resolved_patient_id
    return args


def _assert_patient_allowed(patient_id: str, user_role: str, allowed_patient_ids: list[str]) -> None:
    """Kiểm lại quyền sau khi patient_id được resolve trong lúc chạy.

    Validator không thấy được giá trị đến từ ``$resolve_patient.patient_id``, nên đây là
    chốt chặn thứ hai cho đúng bất biến: USER chỉ chạm hồ sơ của chính mình.
    """
    if user_role != "USER":
        return
    if normalize_patient_id(patient_id) not in allowed_patient_ids:
        raise PolicyError("Tài khoản USER chỉ được truy cập hồ sơ FHIR đã liên kết với chính mình.")


def _enforce_resource_ownership(
    payload: dict[str, Any],
    user_role: str,
    allowed_patient_ids: list[str],
) -> None:
    """Loại resource không thuộc về USER sau khi đã fetch.

    Chỉ có ý nghĩa khi ``agent_allow_user_resource_lookup=true``; khi cờ tắt thì
    validator đã chặn USER từ trước.
    """
    if user_role != "USER" or not get_settings().agent_allow_user_resource_lookup:
        return
    for item in payload.get("evidence") or []:
        owner = _resource_owner(item)
        if owner and owner not in allowed_patient_ids:
            raise PolicyError("Tài khoản USER chỉ được truy cập hồ sơ FHIR đã liên kết với chính mình.")


def _resource_owner(evidence_item: Any) -> str | None:
    if not isinstance(evidence_item, dict):
        return None
    data = evidence_item.get("data")
    if not isinstance(data, dict):
        return None
    if evidence_item.get("resource_type") == "Patient":
        return normalize_patient_id(data.get("id") or evidence_item.get("id"))
    for key in ("patient_id", "subject", "patient"):
        value = data.get(key)
        if isinstance(value, str):
            return normalize_patient_id(value)
        if isinstance(value, dict):
            owner = normalize_patient_id(value.get("id") or value.get("reference"))
            if owner:
                return owner
    return None


def _merge_payloads(payloads: list[dict[str, Any]]) -> dict[str, Any]:
    evidence: list[dict[str, Any]] = []
    answers: list[str] = []
    patient_ids: list[str] = []
    intents: list[str] = []
    all_patients = False
    observation_type: str | None = None

    for payload in payloads:
        evidence.extend(payload.get("evidence") or [])
        answer = (payload.get("answer") or "").strip()
        if answer:
            answers.append(answer)
        patient_id = normalize_patient_id(payload.get("patient_id"))
        if patient_id and patient_id not in patient_ids:
            patient_ids.append(patient_id)
        intent = payload.get("intent")
        if intent and intent not in intents:
            intents.append(intent)
        all_patients = all_patients or bool(payload.get("all_patients"))
        observation_type = observation_type or payload.get("observation_type")

    merged: dict[str, Any] = {
        # Ghép câu trả lời template của từng step: đây là fallback khi answer LLM lỗi,
        # nên nó phải tự đứng được như một câu trả lời hoàn chỉnh.
        "answer": " ".join(answers) if answers else "Không tìm thấy dữ liệu FHIR phù hợp với câu hỏi hiện tại.",
        "intent": intents[0] if len(intents) == 1 else "multi_tool",
        "patient_id": None if all_patients else (patient_ids[0] if len(patient_ids) == 1 else None),
        "evidence": evidence,
        "usage": _zero_usage(),
    }
    if all_patients:
        merged["all_patients"] = True
    if observation_type:
        merged["observation_type"] = observation_type
    return merged


def _build_intent_plan(
    *,
    tool_names: list[str],
    args_list: list[dict[str, Any]],
    payload: dict[str, Any],
    plan: Plan,
    patient_id: str | None,
    usage: dict[str, int | float],
) -> IntentPlan:
    """Dựng IntentPlan để tái dùng ``_finalize_chat_response`` của pipeline hiện tại."""
    if len(tool_names) == 1:
        tool = get_tool(tool_names[0])
        tool_name = (tool.base_tool or tool_names[0]) if tool else tool_names[0]
        all_patients = bool(tool and tool.all_patients)
    else:
        tool_name = MULTI_TOOL_NAME
        all_patients = bool(payload.get("all_patients"))

    observation_type = None
    resource_type = None
    resource_id = None
    term = None
    code = None
    for args in args_list:
        observation_type = observation_type or args.get("observation_type")
        resource_type = resource_type or args.get("resource_type")
        resource_id = resource_id or args.get("resource_id")
        term = term or args.get("term")
        code = code or args.get("code")

    return IntentPlan(
        tool_name=tool_name,
        patient_id=None if all_patients else patient_id,
        resource_type=resource_type,
        resource_id=resource_id,
        observation_type=observation_type,
        term=term,
        code=code,
        all_patients=all_patients,
        explain=plan.explain,
        reason=plan.reason,
        source=f"{PLAN_SOURCE_PREFIX}_{plan.source}",
        usage=usage,
    )


def _single_result(
    plan: Plan,
    outcome: _Outcome,
    plan_usage: dict[str, int | float],
    executed: list[dict[str, Any]],
    resolved_patient_id: str | None,
) -> ExecutionResult:
    payload = outcome.payload
    status = outcome.status or _status_for_payload(payload)
    tool_name = executed[-1]["tool"] if executed else TOOL_SEARCH_PATIENTS
    tool = get_tool(tool_name)
    return ExecutionResult(
        payload=payload,
        plan=IntentPlan(
            tool_name=(tool.base_tool or tool_name) if tool else tool_name,
            patient_id=normalize_patient_id(payload.get("patient_id")) or resolved_patient_id,
            all_patients=bool(tool and tool.all_patients),
            explain=plan.explain,
            reason=plan.reason,
            source=f"{PLAN_SOURCE_PREFIX}_{plan.source}",
            usage=plan_usage,
        ),
        response_status=status,
        resolved_patient_id=resolved_patient_id,
        executed_steps=executed,
    )


def _status_for_payload(payload: dict[str, Any]) -> str:
    if payload.get("needs_patient_selection"):
        return RESPONSE_STATUS_PATIENT_SELECTION
    if payload.get("evidence"):
        return RESPONSE_STATUS_ANSWERED
    if payload.get("external_knowledge"):
        return RESPONSE_STATUS_ANSWERED
    return RESPONSE_STATUS_NO_DATA


def _clarification_payload() -> dict[str, Any]:
    return {
        "answer": CLARIFICATION_ANSWER,
        "intent": "list_patients",
        "patient_id": None,
        "evidence": [],
        "usage": _zero_usage(),
    }


def _tool_error_payload(user_message: str) -> dict[str, Any]:
    return {
        "answer": f"Hệ thống chưa lấy được dữ liệu FHIR: {user_message}",
        "intent": "unknown",
        "patient_id": None,
        "evidence": [],
        "usage": _zero_usage(),
    }
