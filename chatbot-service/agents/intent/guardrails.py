from __future__ import annotations

import dataclasses

from agents.intent.constants import (
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
)
from agents.intent.models import IntentPlan
from agents.intent.observation_utils import infer_observation_type
from agents.intent.patient_utils import (
    has_patient_search_criteria,
    is_patient_list_request,
    extract_patient_search_criteria,
    resolve_explicit_patient_id,
    normalize_patient_id,
)
from agents.intent.text_utils import normalize_text, contains_any
from agents.intent.vocabulary import (
    PATIENT_CONTACT_KEYWORDS,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    CONDITION_KEYWORDS,
)


def enforce_contact_detail_routing(message: str, plan: IntentPlan) -> IntentPlan:
    if plan.tool_name == TOOL_GET_PATIENT:
        return plan
    if has_patient_search_criteria(plan):
        return plan
    if not contains_any(normalize_text(message), PATIENT_CONTACT_KEYWORDS):
        return plan
    return dataclasses.replace(
        plan,
        tool_name=TOOL_GET_PATIENT,
        reason="Contact detail requests are routed to the Patient resource.",
        source=f"{plan.source}_guardrail",
    )


def enforce_patient_list_routing(message: str, plan: IntentPlan) -> IntentPlan:
    if plan.tool_name == TOOL_SEARCH_PATIENTS:
        return plan
    if not is_patient_list_request(message):
        return plan
    return dataclasses.replace(
        plan,
        tool_name=TOOL_SEARCH_PATIENTS,
        reason="Patient list requests are routed to Patient search.",
        source=f"{plan.source}_guardrail",
    )


def apply_all_patient_scope(message: str, plan: IntentPlan) -> IntentPlan:
    if not is_patient_list_request(message) or resolve_explicit_patient_id(message):
        return plan

    text = normalize_text(message)
    tool_name = plan.tool_name
    if contains_any(text, MEDICATION_KEYWORDS):
        tool_name = TOOL_GET_MEDICATIONS
    elif contains_any(text, OBSERVATION_KEYWORDS):
        tool_name = TOOL_GET_OBSERVATIONS
    elif contains_any(text, ENCOUNTER_KEYWORDS):
        tool_name = TOOL_GET_ENCOUNTERS
    elif contains_any(text, CONDITION_KEYWORDS):
        tool_name = TOOL_GET_CONDITIONS

    if tool_name == TOOL_SEARCH_PATIENTS:
        return plan

    return dataclasses.replace(
        plan,
        tool_name=tool_name,
        all_patients=True,
    )


def apply_patient_id_hint(message: str, provided_patient_id: str | None, plan: IntentPlan) -> IntentPlan:
    explicit_patient_id = resolve_explicit_patient_id(message) or normalize_patient_id(provided_patient_id)
    if not explicit_patient_id or explicit_patient_id == plan.patient_id:
        return plan
    return dataclasses.replace(plan, patient_id=explicit_patient_id)


def apply_patient_search_criteria_hint(message: str, plan: IntentPlan) -> IntentPlan:
    if has_patient_search_criteria(plan) or resolve_explicit_patient_id(message):
        return plan

    criteria = extract_patient_search_criteria(message)
    if not criteria:
        return plan

    tool_name = TOOL_SEARCH_PATIENTS if plan.tool_name == TOOL_GET_PATIENT else plan.tool_name
    return dataclasses.replace(
        plan,
        tool_name=tool_name,
        search_name=criteria.get("search_name"),
        search_phone=criteria.get("search_phone"),
        search_birth_date=criteria.get("search_birth_date"),
        search_identifier=criteria.get("search_identifier"),
        source=f"{plan.source}_guardrail",
    )


def add_observation_type_hint(message: str, plan: IntentPlan) -> IntentPlan:
    if plan.tool_name != TOOL_GET_OBSERVATIONS or plan.observation_type:
        return plan
    observation_type = infer_observation_type(message)
    if not observation_type:
        return plan
    return dataclasses.replace(plan, observation_type=observation_type)
