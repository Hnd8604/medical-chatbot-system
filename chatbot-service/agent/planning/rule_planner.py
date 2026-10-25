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
from nlp.observation_types import OBSERVATION_LOINC_CODES, infer_observation_type
from nlp.patient_refs import (
    extract_patient_search_criteria,
    is_contextual_patient_reference,
    is_patient_list_request,
    is_self_patient_reference,
    resolve_explicit_patient_id,
)
from nlp.text import contains_any, normalize_patient_id, normalize_text
from nlp.value_filter import extract_value_filter
from nlp.vocabulary import (
    COHORT_PHRASES,
    CONDITION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    EXPLAIN_KEYWORDS,
    LATEST_VISIT_PHRASES,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    PATIENT_CONTACT_KEYWORDS,
    PATIENT_INFO_KEYWORDS,
    TREND_KEYWORDS,
)
from tools.registry import (
    TOOL_EXPLAIN_CONCEPT,
    TOOL_FHIR_STATUS,
    TOOL_GET_ALL_CONDITIONS,
    TOOL_GET_ALL_ENCOUNTERS,
    TOOL_GET_ALL_MEDICATIONS,
    TOOL_GET_ALL_OBSERVATIONS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATION_TREND,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
)


RESOLVE_STEP_ID = "resolve_patient"
LAST_VISIT_STEP_ID = "last_visit"

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

    latest_visit = contains_any(text, LATEST_VISIT_PHRASES)

    def patient_plan(intents: list[tuple[str, str, dict[str, Any]]], *, explain_mode: bool = False) -> Plan:
        """Một hoặc nhiều loại dữ liệu của cùng một bệnh nhân (hoặc của mọi bệnh nhân)."""
        if all_patients:
            steps = [
                # Tool nhiều bệnh nhân tự có limit mặc định phù hợp (vd 20 cho chỉ số).
                PlanStep(id=f"rule_step_{index}", tool=all_tool, args={k: v for k, v in args.items() if k != "limit"})
                for index, (_, all_tool, args) in enumerate(intents, start=1)
            ]
            return _plan(_renumber(steps), explain_mode, reason)
        steps: list[PlanStep] = []
        patient_ref: dict[str, Any] = {"patient_id": patient_id} if patient_id else {}
        if criteria:
            # Tên/SĐT/ngày sinh trong câu hỏi thắng bệnh nhân đang chọn: chốt bệnh nhân trước.
            steps.append(PlanStep(id=RESOLVE_STEP_ID, tool=TOOL_SEARCH_PATIENTS, args={**criteria, "limit": 3}))
            patient_ref = {"patient_id": f"{STEP_REF_PREFIX}{RESOLVE_STEP_ID}.patient_id"}
        visit_linked = latest_visit and any(tool in _VISIT_FILTERABLE for tool, _, _ in intents)
        if visit_linked:
            # "Thuốc trong lần khám gần nhất": chốt lần khám trước, step sau lọc theo nó.
            steps.append(PlanStep(id=LAST_VISIT_STEP_ID, tool=TOOL_GET_ENCOUNTERS, args={**patient_ref, "limit": 1}))
        for index, (tool, _, args) in enumerate(intents, start=1):
            if visit_linked and tool == TOOL_GET_ENCOUNTERS:
                continue  # lần khám đã có ở step last_visit
            if visit_linked and tool in _VISIT_FILTERABLE:
                args = {**args, "encounter_id": f"{STEP_REF_PREFIX}{LAST_VISIT_STEP_ID}.encounter_id"}
            steps.append(PlanStep(id=f"rule_step_{index}", tool=tool, args={**args, **patient_ref}))
        return _plan(_renumber(steps), explain_mode, reason)

    if is_fhir_status_request(message):
        return single(TOOL_FHIR_STATUS)

    resource_reference = extract_resource_reference(message)
    if resource_reference:
        return single(
            TOOL_GET_RESOURCE,
            {"resource_type": resource_reference[0], "resource_id": resource_reference[1]},
            explain_mode=explain,
        )

    observation_type = infer_observation_type(message)
    value_filter = extract_value_filter(message)
    if observation_type and value_filter and (all_patients or contains_any(text, COHORT_PHRASES)):
        # "Bệnh nhân nào có HbA1c > 7": một truy vấn nhóm, lọc ngưỡng bằng code.
        return single(
            TOOL_GET_ALL_OBSERVATIONS,
            {"observation_type": observation_type, "value_op": value_filter[0], "value": value_filter[1], "limit": 20},
        )

    concept = _concept_term(message, observation_type) if explain else None
    if concept is not None:
        # "HbA1c là gì", "Metformin dùng để làm gì": kiến thức chung, không gắn bệnh nhân.
        return single(TOOL_EXPLAIN_CONCEPT, concept, explain_mode=True)

    if observation_type and contains_any(text, TREND_KEYWORDS) and not all_patients:
        return patient_plan([(TOOL_GET_OBSERVATION_TREND, TOOL_GET_ALL_OBSERVATIONS, {"observation_type": observation_type})])

    intents: list[tuple[str, str, dict[str, Any]]] = []
    if contains_any(text, MEDICATION_KEYWORDS):
        intents.append((TOOL_GET_MEDICATIONS, TOOL_GET_ALL_MEDICATIONS, {"limit": 20}))
    if contains_any(text, CONDITION_KEYWORDS):
        intents.append((TOOL_GET_CONDITIONS, TOOL_GET_ALL_CONDITIONS, {"limit": 20}))
    if contains_any(text, OBSERVATION_KEYWORDS) or observation_type:
        args: dict[str, Any] = {"limit": 5}
        if observation_type:
            args["observation_type"] = observation_type
        intents.append((TOOL_GET_OBSERVATIONS, TOOL_GET_ALL_OBSERVATIONS, args))
    if contains_any(text, ENCOUNTER_KEYWORDS):
        intents.append((TOOL_GET_ENCOUNTERS, TOOL_GET_ALL_ENCOUNTERS, {"limit": 5}))
    if intents:
        return patient_plan(intents, explain_mode=explain)

    if contains_any(text, PATIENT_CONTACT_KEYWORDS):
        if criteria:
            return single(TOOL_SEARCH_PATIENTS, {**criteria, "limit": 20})
        return single(TOOL_GET_PATIENT, {"patient_id": patient_id} if patient_id else {})
    if is_self_patient_reference(message):
        return single(TOOL_GET_PATIENT, {"patient_id": patient_id} if patient_id else {})
    if all_patients or criteria:
        return single(TOOL_SEARCH_PATIENTS, {**criteria, "limit": 20})
    if contains_any(text, PATIENT_INFO_KEYWORDS):
        return single(TOOL_GET_PATIENT, {"patient_id": patient_id} if patient_id else {})
    return None


# Tool lọc được theo lần khám (encounter_id).
_VISIT_FILTERABLE = frozenset({TOOL_GET_MEDICATIONS, TOOL_GET_CONDITIONS, TOOL_GET_OBSERVATIONS})

# Cụm từ hỏi/giải thích bị bỏ khi rút thuật ngữ cho explain_concept.
_CONCEPT_NOISE = re.compile(
    r"(có nghĩa là gì|nghĩa là gì|có ý nghĩa gì|ý nghĩa của|ý nghĩa|là gì|dùng để làm gì|dùng để|"
    r"công dụng của|công dụng|tác dụng của|tác dụng|giải thích giúp tôi|giải thích|cho tôi biết|"
    r"what is|what does|meaning of|explain|thuốc|chỉ số|xét nghiệm|bệnh|[?!.,:])",
    re.IGNORECASE,
)
# Câu nhắc tới bệnh nhân / resource cụ thể thì không phải câu hỏi khái niệm chung.
# ("đó" và "đo"/"độ" trùng nhau khi bỏ dấu nên chỉ bắt chỉ định đi sau danh từ dữ liệu.)
_SPECIFIC_REFERENCE = re.compile(
    r"\b(thuoc|chi so|xet nghiem|ket qua|chan doan|benh|ma|loai|cai) (nay|do|kia|ay)\b"
    r"|\b(cua toi|cua minh|cua em|cua tui|toi dang|toi duoc)\b"
)


def _concept_term(message: str, observation_type: str | None) -> dict[str, Any] | None:
    """Thuật ngữ cho explain_concept nếu đây là câu hỏi kiến thức chung, ngược lại None."""
    text = normalize_text(message)
    if (
        resolve_explicit_patient_id(message)
        or is_contextual_patient_reference(message)
        or is_self_patient_reference(message)
        or _SPECIFIC_REFERENCE.search(text)
        or extract_patient_search_criteria(message)
    ):
        return None
    term = re.sub(r"\s+", " ", _CONCEPT_NOISE.sub(" ", message)).strip()
    if len(term) < 2:
        return None
    args: dict[str, Any] = {"term": term}
    codes = OBSERVATION_LOINC_CODES.get(observation_type or "")
    if codes:
        args["code"] = codes[0]
    return args


def _renumber(steps: list[PlanStep]) -> list[PlanStep]:
    """Plan một step giữ id "rule_step" như trước (log/test cũ dựa vào nó)."""
    data_steps = [step for step in steps if step.id.startswith("rule_step")]
    if len(data_steps) != 1:
        return steps
    return [PlanStep(id="rule_step", tool=step.tool, args=step.args) if step.id.startswith("rule_step") else step for step in steps]


def _plan(steps: list[PlanStep], explain: bool, reason: str | None) -> Plan:
    return Plan(
        steps=tuple(steps),
        answer_mode=ANSWER_MODE_EXPLAIN if explain else ANSWER_MODE_DATA_ONLY,
        reason=reason or "Lập kế hoạch bằng luật (không dùng LLM).",
        source=PLAN_SOURCE_RULES,
    )
