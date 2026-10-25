"""Chạy plan đã validate và gộp kết quả thành một payload.

1. **Chốt bệnh nhân trước** (step ``search_patients``): nhiều bệnh nhân khớp ⇒ dừng hỏi
   lại người dùng *trước khi* chạm dữ liệu bệnh nhân nào.
2. **Các step còn lại chạy theo đợt**: step độc lập chạy song song (``asyncio.gather``);
   step tham chiếu kết quả step trước (``"$last_visit.encounter_id"``) chờ step đó xong.
   Step mà tham chiếu không có giá trị (vd không có lần khám nào) được bỏ qua.
3. **Lỗi từng phần**: một step lỗi FHIR không huỷ cả lượt — còn ít nhất một step chạy
   được thì trả ``partial`` kèm ``failed_steps``; lỗi hết mới là ``tool_error``.
4. **Không dựng lại payload từ đầu**: tool đã trả payload chuẩn kèm câu template tiếng
   Việt, executor chỉ ghép.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any

from agent.errors import PolicyError
from agent.planning.plan import STEP_REF_PREFIX, Plan, PlanStep
from agent.state import (
    RESPONSE_STATUS_ANSWERED,
    RESPONSE_STATUS_CLARIFICATION,
    RESPONSE_STATUS_NO_DATA,
    RESPONSE_STATUS_PARTIAL,
    RESPONSE_STATUS_PATIENT_SELECTION,
    RESPONSE_STATUS_TOOL_ERROR,
)
from app.config import get_settings
from fhir.client import FhirClient, FhirClientError
from nlp.text import normalize_patient_id
from tools.registry import (
    PATIENT_SCOPED_TOOLS,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
    base_tool_name,
    get_tool,
)


log = logging.getLogger(__name__)

MULTI_TOOL_NAME = "multi_tool_plan"

ERROR_FHIR = "fhir_error"
ERROR_UNKNOWN_TOOL = "unknown_tool"

CLARIFICATION_ANSWER = (
    "Mình chưa xác định được bệnh nhân cụ thể. "
    "Hãy chọn bệnh nhân trên giao diện hoặc cung cấp mã bệnh nhân, tên, "
    "số điện thoại, ngày sinh hay mã định danh."
)


@dataclass
class ExecutionResult:
    payload: dict[str, Any]
    response_status: str
    # Tên tool cho response/audit: tool gốc nếu một step, ``multi_tool_plan`` nếu nhiều step.
    tool_name: str
    resolved_patient_id: str | None = None
    executed_steps: list[dict[str, Any]] = field(default_factory=list)
    # [{id, tool, error_code}] của các step lỗi khi lượt vẫn trả được dữ liệu (partial).
    failed_steps: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class _Outcome:
    payload: dict[str, Any]
    # None = chạy bình thường; còn lại là status kết thúc lượt sớm.
    status: str | None = None
    error_code: str | None = None


async def execute_plan(
    plan: Plan,
    *,
    client: FhirClient,
    user_role: str,
    allowed_patient_ids: list[str],
    provided_patient_id: str | None,
) -> ExecutionResult:
    resolved_patient_id = normalize_patient_id(provided_patient_id)
    executed: list[dict[str, Any]] = []

    search_steps = [step for step in plan.steps if step.tool == TOOL_SEARCH_PATIENTS]
    other_steps = [step for step in plan.steps if step.tool != TOOL_SEARCH_PATIENTS]

    # --- Bước 1: chốt bệnh nhân trước, vì các step còn lại có thể phụ thuộc vào nó.
    for step in search_steps:
        outcome = await _run_step(client, step, step.args)
        executed.append(_executed(step, step.args))
        if outcome.status is not None:
            return _early_result(outcome, executed, resolved_patient_id)

        payload = outcome.payload
        if payload.get("needs_patient_selection"):
            # Nhiều bệnh nhân khớp: dừng lại hỏi người dùng thay vì đoán.
            return _early_result(_Outcome(payload, RESPONSE_STATUS_PATIENT_SELECTION), executed, resolved_patient_id)
        found = normalize_patient_id(payload.get("patient_id"))
        if not other_steps:
            # search_patients là step cuối: kết quả tìm (một người hay danh sách) chính là câu
            # trả lời. Trước đây khớp đúng MỘT người lại rơi xuống nhánh "hỏi lại".
            return _early_result(_Outcome(payload, status_for_payload(payload)), executed, found or resolved_patient_id)
        if found:
            resolved_patient_id = found
        else:
            return _early_result(_clarification(), executed, resolved_patient_id)

    # --- Bước 2: bơm patient_id đã chốt vào các step còn thiếu, kiểm quyền lần hai.
    prepared: list[tuple[PlanStep, dict[str, Any]]] = []
    for step in other_steps:
        args = _fill_patient_id(step.args, resolved_patient_id)
        if step.tool in PATIENT_SCOPED_TOOLS:
            if not args.get("patient_id"):
                return _early_result(_clarification(), executed, resolved_patient_id)
            _assert_patient_allowed(args["patient_id"], user_role, allowed_patient_ids)
        prepared.append((step, args))

    if not prepared:
        return _early_result(_clarification(), executed, resolved_patient_id)

    # --- Bước 3: chạy theo đợt — step độc lập song song, step phụ thuộc chờ step nó trỏ tới.
    succeeded: list[tuple[PlanStep, _Outcome]] = []
    failed: list[tuple[PlanStep, _Outcome]] = []
    finished: dict[str, _Outcome] = {}
    pending = list(prepared)
    while pending:
        ready = [(step, args) for step, args in pending if _dependencies(args) <= finished.keys()]
        if not ready:  # validator chỉ cho trỏ về step đứng trước nên không thể xảy ra
            break
        pending = [item for item in pending if item not in ready]
        runnable: list[tuple[PlanStep, dict[str, Any]]] = []
        for step, args in ready:
            resolved = _resolve_refs(args, finished)
            if resolved is None:
                # Step được trỏ tới lỗi hoặc không có kết quả (vd không có lần khám nào):
                # câu trả lời của step đó đã nói lên điều này, bỏ qua step phụ thuộc.
                finished[step.id] = _Outcome({}, RESPONSE_STATUS_NO_DATA)
                continue
            runnable.append((step, resolved))
        outcomes = await asyncio.gather(*(_run_step(client, step, args) for step, args in runnable))
        for (step, args), outcome in zip(runnable, outcomes):
            finished[step.id] = outcome
            executed.append(_executed(step, args))
            (succeeded if outcome.status is None else failed).append((step, outcome))

    failed_steps = [
        {"id": step.id, "tool": step.tool, "error_code": outcome.error_code or ERROR_FHIR}
        for step, outcome in failed
    ]
    if not succeeded:
        # Lỗi hết: không có gì để trả lời, giữ câu giải thích sự cố của step đầu tiên.
        result = _early_result(failed[0][1], executed, resolved_patient_id)
        result.failed_steps = failed_steps
        return result

    for step, outcome in succeeded:
        if step.tool == TOOL_GET_RESOURCE:
            _enforce_resource_ownership(outcome.payload, user_role, allowed_patient_ids)

    payloads = [outcome.payload for _, outcome in succeeded]
    if len(prepared) == 1:
        merged = payloads[0]
        tool_name = base_tool_name(prepared[0][0].tool)
    else:
        merged = _merge_payloads(payloads)
        tool_name = MULTI_TOOL_NAME

    resolved_patient_id = normalize_patient_id(merged.get("patient_id")) or resolved_patient_id
    return ExecutionResult(
        payload=merged,
        response_status=RESPONSE_STATUS_PARTIAL if failed else status_for_payload(merged),
        tool_name=tool_name,
        resolved_patient_id=resolved_patient_id,
        executed_steps=executed,
        failed_steps=failed_steps,
    )


async def _run_step(client: FhirClient, step: PlanStep, args: dict[str, Any]) -> _Outcome:
    tool = get_tool(step.tool)
    if tool is None:  # đã chặn ở validator; giữ để phòng thủ chiều sâu
        return _Outcome(
            _tool_error_payload(f"Không tìm thấy tool {step.tool}."),
            RESPONSE_STATUS_TOOL_ERROR,
            ERROR_UNKNOWN_TOOL,
        )
    try:
        return _Outcome(await tool.run(client, args))
    except FhirClientError as exc:
        # Trả payload thay vì 502: người dùng nhận được câu giải thích sự cố, và
        # response_status=tool_error cho phép Spring/FE phân biệt với câu trả lời thật.
        log.warning("FHIR tool %s lỗi: %s", step.tool, exc)
        return _Outcome(_tool_error_payload(exc.user_message), RESPONSE_STATUS_TOOL_ERROR, ERROR_FHIR)


def _dependencies(args: dict[str, Any]) -> set[str]:
    return {
        value[len(STEP_REF_PREFIX):].split(".", 1)[0]
        for value in args.values()
        if isinstance(value, str) and value.startswith(STEP_REF_PREFIX)
    }


def _resolve_refs(args: dict[str, Any], finished: dict[str, "_Outcome"]) -> dict[str, Any] | None:
    """Thay "$step.field" bằng giá trị thật; None nếu step được trỏ tới không cho giá trị."""
    resolved = dict(args)
    for key, value in args.items():
        if not (isinstance(value, str) and value.startswith(STEP_REF_PREFIX)):
            continue
        target, _, field = value[len(STEP_REF_PREFIX):].partition(".")
        outcome = finished.get(target)
        if outcome is None or outcome.status is not None:
            return None
        actual = _ref_value(outcome.payload, field)
        if not actual:
            return None
        resolved[key] = actual
    return resolved


def _ref_value(payload: dict[str, Any], field: str) -> str | None:
    if field == "patient_id":
        return normalize_patient_id(payload.get("patient_id"))
    if field == "encounter_id":
        # Evidence của get_encounters xếp mới nhất trước ⇒ phần tử đầu là lần khám gần nhất.
        for item in payload.get("evidence") or []:
            if isinstance(item, dict) and item.get("resource_type") == "Encounter" and item.get("id"):
                return str(item["id"])
    return None


def _executed(step: PlanStep, args: dict[str, Any]) -> dict[str, Any]:
    return {"id": step.id, "tool": step.tool, "args": args}


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
    """Chặn resource không thuộc về USER sau khi đã fetch.

    Chỉ có ý nghĩa khi ``allow_user_resource_lookup=true``; khi cờ tắt thì validator đã
    chặn USER từ trước.
    """
    if user_role != "USER" or not get_settings().allow_user_resource_lookup:
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
    external_knowledge: list[dict[str, Any]] = []

    for payload in payloads:
        evidence.extend(payload.get("evidence") or [])
        external_knowledge.extend(payload.get("external_knowledge") or [])
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
        # Ghép câu template của từng step: đây là fallback khi answer LLM lỗi, nên nó
        # phải tự đứng được như một câu trả lời hoàn chỉnh.
        "answer": " ".join(answers) if answers else "Không tìm thấy dữ liệu FHIR phù hợp với câu hỏi hiện tại.",
        "intent": intents[0] if len(intents) == 1 else "multi_tool",
        "patient_id": None if all_patients else (patient_ids[0] if len(patient_ids) == 1 else None),
        "evidence": evidence,
    }
    if all_patients:
        merged["all_patients"] = True
    if observation_type:
        merged["observation_type"] = observation_type
    if external_knowledge:
        merged["external_knowledge"] = external_knowledge
    return merged


def _early_result(
    outcome: _Outcome,
    executed: list[dict[str, Any]],
    resolved_patient_id: str | None,
) -> ExecutionResult:
    payload = outcome.payload
    tool = executed[-1]["tool"] if executed else TOOL_SEARCH_PATIENTS
    return ExecutionResult(
        payload=payload,
        response_status=outcome.status or status_for_payload(payload),
        tool_name=base_tool_name(tool),
        resolved_patient_id=normalize_patient_id(payload.get("patient_id")) or resolved_patient_id,
        executed_steps=executed,
    )


def status_for_payload(payload: dict[str, Any]) -> str:
    if payload.get("needs_patient_selection"):
        return RESPONSE_STATUS_PATIENT_SELECTION
    if payload.get("evidence") or payload.get("external_knowledge"):
        return RESPONSE_STATUS_ANSWERED
    return RESPONSE_STATUS_NO_DATA


def _clarification() -> _Outcome:
    return _Outcome(clarification_payload(), RESPONSE_STATUS_CLARIFICATION)


def clarification_payload(answer: str = CLARIFICATION_ANSWER) -> dict[str, Any]:
    return {
        "answer": answer,
        "intent": "list_patients",
        "patient_id": None,
        "evidence": [],
    }


def _tool_error_payload(user_message: str) -> dict[str, Any]:
    return {
        "answer": f"Hệ thống chưa lấy được dữ liệu FHIR: {user_message}",
        "intent": "unknown",
        "patient_id": None,
        "evidence": [],
    }
