"""Planner dựa trên luật — đường lui tất định, không tốn LLM call.

Dùng khi: không có gateway LLM, planner LLM lỗi/trả JSON hỏng, hoặc low-cost mode (quota
sắp cạn). Kết quả vẫn phải đi qua validator như plan của LLM.

Trả ``None`` khi không nhận ra câu hỏi dữ liệu nào — graph khi đó hỏi lại người dùng
thay vì đoán bừa một truy vấn FHIR.
"""

from __future__ import annotations

import re
from typing import Any

from agent.planning.plan import PLAN_SOURCE_RULES, STEP_REF_PREFIX, Plan, PlanStep
from agent.state import ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN
from nlp.observation_types import infer_observation_type
from nlp.patient_refs import (
    extract_patient_search_criteria,
    is_patient_list_request,
    is_self_patient_reference,
    resolve_explicit_patient_id,
)
from nlp.text import contains_any, normalize_patient_id, normalize_text
from nlp.vocabulary import (
    CONDITION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    EXPLAIN_KEYWORDS,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    PATIENT_CONTACT_KEYWORDS,
    PATIENT_INFO_KEYWORDS,
)
from tools.registry import (
    TOOL_FHIR_STATUS,
    TOOL_GET_ALL_CONDITIONS,
    TOOL_GET_ALL_ENCOUNTERS,
    TOOL_GET_ALL_MEDICATIONS,
    TOOL_GET_ALL_OBSERVATIONS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
)


RESOLVE_STEP_ID = "resolve_patient"

# Tham chiếu resource FHIR cụ thể (không tính Patient — Patient/... đã là patient_id).
_RESOURCE_REFERENCE_PATTERN = re.compile(
    r"\b(Encounter|Observation|Condition|MedicationRequest)/([A-Za-z0-9][A-Za-z0-9\-\.]*)",
    re.IGNORECASE,
)
_CANONICAL_RESOURCE_TYPES = {
    name.lower(): name
    for name in ("Patient", "Encounter", "Observation", "Condition", "MedicationRequest")
}

FHIR_STATUS_KEYWORDS = ["status", "trang thai", "hoat dong", "ket noi", "san sang", "health"]


def extract_resource_reference(message: str) -> tuple[str, str] | None:
    match = _RESOURCE_REFERENCE_PATTERN.search(message or "")
    if not match:
        return None
    return _CANONICAL_RESOURCE_TYPES[match.group(1).lower()], match.group(2)


def is_fhir_status_request(message: str) -> bool:
    text = normalize_text(message)
    return "fhir" in text and contains_any(text, FHIR_STATUS_KEYWORDS)


def rule_plan(message: str, *, provided_patient_id: str | None = None, reason: str | None = None) -> Plan | None:
    text = normalize_text(message)
    patient_id = resolve_explicit_patient_id(message) or normalize_patient_id(provided_patient_id)
    criteria = extract_patient_search_criteria(message)
    all_patients = is_patient_list_request(message) and not resolve_explicit_patient_id(message)
    explain = contains_any(text, EXPLAIN_KEYWORDS)

    def single(tool: str, args: dict[str, Any] | None = None, *, explain_mode: bool = False) -> Plan:
        return _plan([PlanStep(id="rule_step", tool=tool, args=args or {})], explain_mode, reason)

    def patient_data(tool: str, all_tool: str, args: dict[str, Any], *, explain_mode: bool = False) -> Plan:
        if all_patients:
            return single(all_tool, args, explain_mode=explain_mode)
        if criteria:
            # Tên/SĐT/ngày sinh trong câu hỏi thắng bệnh nhân đang chọn: chốt bệnh nhân trước.
            return _plan(
                [
                    PlanStep(id=RESOLVE_STEP_ID, tool=TOOL_SEARCH_PATIENTS, args={**criteria, "limit": 3}),
                    PlanStep(
                        id="rule_step",
                        tool=tool,
                        args={**args, "patient_id": f"{STEP_REF_PREFIX}{RESOLVE_STEP_ID}.patient_id"},
                    ),
                ],
                explain_mode,
                reason,
            )
        return single(tool, {**args, "patient_id": patient_id} if patient_id else args, explain_mode=explain_mode)

    if is_fhir_status_request(message):
        return single(TOOL_FHIR_STATUS)

    resource_reference = extract_resource_reference(message)
    if resource_reference:
        return single(
            TOOL_GET_RESOURCE,
            {"resource_type": resource_reference[0], "resource_id": resource_reference[1]},
            explain_mode=explain,
        )

    if is_self_patient_reference(message):
        return single(TOOL_GET_PATIENT, {"patient_id": patient_id} if patient_id else {})

    if contains_any(text, MEDICATION_KEYWORDS):
        return patient_data(TOOL_GET_MEDICATIONS, TOOL_GET_ALL_MEDICATIONS, {"limit": 20}, explain_mode=explain)
    if contains_any(text, OBSERVATION_KEYWORDS):
        args: dict[str, Any] = {"limit": 5}
        observation_type = infer_observation_type(message)
        if observation_type:
            args["observation_type"] = observation_type
        return patient_data(TOOL_GET_OBSERVATIONS, TOOL_GET_ALL_OBSERVATIONS, args, explain_mode=explain)
    if contains_any(text, ENCOUNTER_KEYWORDS):
        return patient_data(TOOL_GET_ENCOUNTERS, TOOL_GET_ALL_ENCOUNTERS, {"limit": 5})
    if contains_any(text, PATIENT_CONTACT_KEYWORDS):
        if criteria:
            return single(TOOL_SEARCH_PATIENTS, {**criteria, "limit": 20})
        return single(TOOL_GET_PATIENT, {"patient_id": patient_id} if patient_id else {})
    if contains_any(text, CONDITION_KEYWORDS):
        return patient_data(TOOL_GET_CONDITIONS, TOOL_GET_ALL_CONDITIONS, {"limit": 20}, explain_mode=explain)
    if all_patients or criteria:
        return single(TOOL_SEARCH_PATIENTS, {**criteria, "limit": 20})
    if contains_any(text, PATIENT_INFO_KEYWORDS):
        return single(TOOL_GET_PATIENT, {"patient_id": patient_id} if patient_id else {})
    return None


def _plan(steps: list[PlanStep], explain: bool, reason: str | None) -> Plan:
    return Plan(
        steps=tuple(steps),
        answer_mode=ANSWER_MODE_EXPLAIN if explain else ANSWER_MODE_DATA_ONLY,
        reason=reason or "Lập kế hoạch bằng luật (không dùng LLM).",
        source=PLAN_SOURCE_RULES,
    )
