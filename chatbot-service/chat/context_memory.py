from dataclasses import replace
from typing import Any

from agents.intent_extractor import (
    CONDITION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    PATIENT_CONTACT_KEYWORDS,
    PATIENT_INFO_KEYWORDS,
    PATIENT_LIST_KEYWORDS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_UNSUPPORTED,
    IntentPlan,
    extract_patient_search_criteria,
    has_patient_search_criteria,
    normalize_patient_id,
    normalize_text,
    resolve_patient_id_for_request,
)
from api.chat_schemas import ChatRequest
from chat.formatters import _format_resource_summary
from chat.resource_answerers import _evidence
from chat.response_builder import _zero_usage
from chat.text_helpers import _contains_any
from fhir.client import FhirClient
from fhir.normalizer import (
    normalize_condition,
    normalize_encounter,
    normalize_medication_request,
    normalize_observation,
    normalize_patient,
)


def _detect_intent(message: str) -> str:
    text = normalize_text(message)
    if extract_patient_search_criteria(message):
        return "patients"
    if _contains_any(text, PATIENT_LIST_KEYWORDS):
        return "patients"
    if _contains_any(text, MEDICATION_KEYWORDS):
        return "medications"
    if _contains_any(text, OBSERVATION_KEYWORDS):
        return "observations"
    if _contains_any(text, ENCOUNTER_KEYWORDS):
        return "encounters"
    if _contains_any(text, PATIENT_CONTACT_KEYWORDS):
        return "patient"
    if _contains_any(text, CONDITION_KEYWORDS):
        return "conditions"
    if _contains_any(text, PATIENT_INFO_KEYWORDS):
        return "patient"
    return "unknown"

def _resolve_patient_id(request: ChatRequest) -> str:
    return resolve_patient_id_for_request(request.message, _patient_id_hint(request))

def _patient_id_hint(request: ChatRequest) -> str | None:
    request_patient_id = normalize_patient_id(request.patient_id)
    if request_patient_id:
        return request_patient_id
    if request.conversation_context:
        return normalize_patient_id(request.conversation_context.active_patient_id)
    return None

def _apply_selected_patient_context(request: ChatRequest, plan: IntentPlan) -> IntentPlan:
    selected_patient_id = _patient_id_hint(request)
    if not selected_patient_id or not has_patient_search_criteria(plan):
        return plan

    text = normalize_text(request.message)
    tool_name = plan.tool_name
    if tool_name == TOOL_SEARCH_PATIENTS:
        if _contains_any(text, PATIENT_CONTACT_KEYWORDS) or _contains_any(text, PATIENT_INFO_KEYWORDS):
            tool_name = TOOL_GET_PATIENT
        elif _contains_any(text, MEDICATION_KEYWORDS):
            tool_name = TOOL_GET_MEDICATIONS
        elif _contains_any(text, OBSERVATION_KEYWORDS):
            tool_name = TOOL_GET_OBSERVATIONS
        elif _contains_any(text, ENCOUNTER_KEYWORDS):
            tool_name = TOOL_GET_ENCOUNTERS
        elif _contains_any(text, CONDITION_KEYWORDS):
            tool_name = TOOL_GET_CONDITIONS
        else:
            return plan

    return replace(
        plan,
        tool_name=tool_name,
        patient_id=selected_patient_id,
        search_name=None,
        search_phone=None,
        search_birth_date=None,
        search_identifier=None,
        all_patients=False,
        source=f"{plan.source}_selected_patient",
    )

def _apply_context_reference_context(request: ChatRequest, plan: IntentPlan) -> IntentPlan:
    context = request.conversation_context
    if not context or not _is_context_reference_question(request.message):
        return plan

    resource_type = _canonical_resource_type(context.last_resource_type)
    tool_name = _tool_for_resource_type(resource_type)
    if not tool_name:
        return plan
    if plan.tool_name not in {TOOL_UNSUPPORTED, tool_name}:
        return plan

    patient_id = _patient_id_hint(request) or plan.patient_id
    return replace(
        plan,
        tool_name=tool_name,
        patient_id=patient_id,
        search_name=None,
        search_phone=None,
        search_birth_date=None,
        search_identifier=None,
        all_patients=False,
        source=f"{plan.source}_context_reference",
    )

async def _answer_context_resource_if_applicable(
    client: FhirClient,
    request: ChatRequest,
    plan: IntentPlan,
) -> dict[str, Any] | None:
    context = request.conversation_context
    if not context or not _is_context_reference_question(request.message):
        return None

    resource_type = _canonical_resource_type(context.last_resource_type)
    resource_id = context.last_resource_id
    if not resource_type or not resource_id:
        return None
    if _tool_for_resource_type(resource_type) != plan.tool_name:
        return None

    resource = await client.get_resource(resource_type, resource_id)
    normalized = _normalize_single_resource(resource_type, resource)
    if not normalized:
        return None

    patient_id = _patient_id_hint(request) or _patient_id_from_resource(normalized) or plan.patient_id
    summary = _format_resource_summary(resource_type, normalized)
    return {
        "answer": (
            f"Theo ngu canh phien chat, tai nguyen gan nhat la "
            f"{resource_type}/{resource_id}: {summary}."
        ),
        "intent": plan.intent,
        "patient_id": patient_id,
        "evidence": [_evidence(resource_type, normalized.get("id"), summary, normalized)],
        "usage": _zero_usage(),
    }

def _is_context_reference_question(message: str) -> bool:
    text = f" {normalize_text(message)} "
    phrases = [
        " cai do ",
        " muc do ",
        " chi so do ",
        " ket qua do ",
        " thuoc do ",
        " lan kham do ",
        " benh do ",
        " chan doan do ",
        " no ",
        " nay ",
        " do ",
        " vua roi ",
        " truoc do ",
        " gan nhat ",
    ]
    return any(phrase in text for phrase in phrases)

def _canonical_resource_type(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip().lower()
    mapping = {
        "patient": "Patient",
        "observation": "Observation",
        "encounter": "Encounter",
        "condition": "Condition",
        "medicationrequest": "MedicationRequest",
        "medication_request": "MedicationRequest",
        "medication-request": "MedicationRequest",
    }
    return mapping.get(normalized)

def _tool_for_resource_type(resource_type: str | None) -> str | None:
    return {
        "Patient": TOOL_GET_PATIENT,
        "Observation": TOOL_GET_OBSERVATIONS,
        "Encounter": TOOL_GET_ENCOUNTERS,
        "Condition": TOOL_GET_CONDITIONS,
        "MedicationRequest": TOOL_GET_MEDICATIONS,
    }.get(resource_type or "")

def _normalize_single_resource(resource_type: str, resource: dict[str, Any]) -> dict[str, Any] | None:
    if resource_type == "Patient":
        return normalize_patient(resource)
    if resource_type == "Observation":
        return normalize_observation(resource)
    if resource_type == "Encounter":
        return normalize_encounter(resource)
    if resource_type == "Condition":
        return normalize_condition(resource)
    if resource_type == "MedicationRequest":
        return normalize_medication_request(resource)
    return None

def _patient_id_from_resource(resource: dict[str, Any]) -> str | None:
    if resource.get("resource_type") == "Patient":
        return resource.get("id")
    subject = resource.get("subject")
    if isinstance(subject, str) and subject.startswith("Patient/"):
        return subject.removeprefix("Patient/")
    patient = resource.get("patient")
    if isinstance(patient, dict):
        patient_id = patient.get("id")
        if isinstance(patient_id, str):
            return patient_id
    return None
