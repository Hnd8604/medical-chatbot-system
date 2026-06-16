from __future__ import annotations

from agents.intent.constants import (
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_MEDICATIONS,
    TOOL_UNSUPPORTED,
)
from agents.intent.models import IntentPlan
from agents.intent.observation_utils import infer_observation_type
from agents.intent.patient_utils import (
    resolve_patient_id_for_request,
    is_patient_list_request,
    has_patient_search_criteria,
    extract_patient_search_criteria,
    resolve_explicit_patient_id,
)
from agents.intent.text_utils import normalize_text, contains_any
from agents.intent.vocabulary import (
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    PATIENT_CONTACT_KEYWORDS,
    CONDITION_KEYWORDS,
    PATIENT_INFO_KEYWORDS,
)


def _compute_rule_confidence(
    *,
    matched: bool,
    has_criteria: bool,
    all_patient_scope: bool,
    tool_name: str,
) -> float:
    if tool_name == TOOL_UNSUPPORTED:
        return 0.30
    if matched and has_criteria:
        return 0.90
    if matched:
        return 0.85
    if all_patient_scope:
        return 0.75
    if has_criteria:
        return 0.70
    # PATIENT_INFO_KEYWORDS fallthrough
    return 0.65


class RuleBasedIntentExtractor:
    async def extract(self, message: str, provided_patient_id: str | None = None) -> IntentPlan:
        patient_id = resolve_patient_id_for_request(message, provided_patient_id)
        text = normalize_text(message)
        search_criteria = extract_patient_search_criteria(message)
        has_criteria = bool(search_criteria)
        all_patient_scope = is_patient_list_request(message) and not resolve_explicit_patient_id(message)

        if contains_any(text, MEDICATION_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_MEDICATIONS,
                patient_id=patient_id,
                **search_criteria,
                limit=20,
                all_patients=all_patient_scope,
                confidence_score=_compute_rule_confidence(
                    matched=True, has_criteria=has_criteria,
                    all_patient_scope=all_patient_scope, tool_name=TOOL_GET_MEDICATIONS,
                ),
            )
        if contains_any(text, OBSERVATION_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_OBSERVATIONS,
                patient_id=patient_id,
                **search_criteria,
                observation_type=infer_observation_type(message),
                limit=5,
                all_patients=all_patient_scope,
                confidence_score=_compute_rule_confidence(
                    matched=True, has_criteria=has_criteria,
                    all_patient_scope=all_patient_scope, tool_name=TOOL_GET_OBSERVATIONS,
                ),
            )
        if contains_any(text, ENCOUNTER_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_ENCOUNTERS,
                patient_id=patient_id,
                **search_criteria,
                limit=5,
                all_patients=all_patient_scope,
                confidence_score=_compute_rule_confidence(
                    matched=True, has_criteria=has_criteria,
                    all_patient_scope=all_patient_scope, tool_name=TOOL_GET_ENCOUNTERS,
                ),
            )
        if contains_any(text, PATIENT_CONTACT_KEYWORDS):
            tool_name = TOOL_SEARCH_PATIENTS if has_criteria else TOOL_GET_PATIENT
            return IntentPlan(
                tool_name=tool_name,
                patient_id=patient_id,
                **search_criteria,
                confidence_score=_compute_rule_confidence(
                    matched=True, has_criteria=has_criteria,
                    all_patient_scope=all_patient_scope, tool_name=tool_name,
                ),
            )
        if contains_any(text, CONDITION_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_CONDITIONS,
                patient_id=patient_id,
                **search_criteria,
                limit=20,
                all_patients=all_patient_scope,
                confidence_score=_compute_rule_confidence(
                    matched=True, has_criteria=has_criteria,
                    all_patient_scope=all_patient_scope, tool_name=TOOL_GET_CONDITIONS,
                ),
            )
        if all_patient_scope or has_criteria:
            return IntentPlan(
                tool_name=TOOL_SEARCH_PATIENTS,
                patient_id=patient_id,
                **search_criteria,
                limit=20,
                confidence_score=_compute_rule_confidence(
                    matched=False, has_criteria=has_criteria,
                    all_patient_scope=all_patient_scope, tool_name=TOOL_SEARCH_PATIENTS,
                ),
            )
        if contains_any(text, PATIENT_INFO_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_PATIENT,
                patient_id=patient_id,
                confidence_score=0.65,
            )

        return IntentPlan(
            tool_name=TOOL_UNSUPPORTED,
            patient_id=patient_id,
            reason="Chưa phát hiện được intent truy xuất FHIR được hỗ trợ.",
            confidence_score=0.30,
        )
