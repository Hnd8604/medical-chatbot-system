"""
Backward-compatible re-export shim.
Logic đã được chuyển sang agents/intent/*.
Mọi import cũ (from agents.intent_extractor import X) vẫn hoạt động bình thường.
"""
from __future__ import annotations

from agents.intent.constants import (
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_MEDICATIONS,
    TOOL_UNSUPPORTED,
    FHIR_PROTECTED_TOOLS,
    TOOL_TO_INTENT,
)
from agents.intent.vocabulary import (
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    PATIENT_CONTACT_KEYWORDS,
    PATIENT_LIST_KEYWORDS,
    CONDITION_KEYWORDS,
    PATIENT_INFO_KEYWORDS,
)
from agents.intent.models import IntentPlan, IntentExtractor
from agents.intent.text_utils import (
    normalize_text,
    contains_any,
    string_or_none,
    clamp,
    extract_phone,
    normalize_phone,
    extract_birth_date,
    normalize_birth_date,
    extract_identifier,
    extract_patient_name,
    clean_name_candidate,
    normalize_patient_id,
)
from agents.intent.patient_utils import (
    resolve_patient_id_for_request,
    resolve_explicit_patient_id,
    resolve_patient_id,
    extract_patient_search_criteria,
    has_patient_search_criteria,
    is_patient_list_request,
    is_self_patient_reference,
)
from agents.intent.observation_utils import infer_observation_type
from agents.intent.guardrails import (
    enforce_contact_detail_routing,
    enforce_patient_list_routing,
    apply_all_patient_scope,
    apply_patient_id_hint,
    apply_patient_search_criteria_hint,
    add_observation_type_hint,
)
from agents.intent.fhir_tools import FHIR_TOOL_DEFINITIONS
from agents.intent.rule_extractor import RuleBasedIntentExtractor
from agents.intent.llm_extractor import LLMIntentExtractor
from agents.intent.factory import get_intent_extractor

# plan_from_tool_call và parse_tool_arguments đã được nội hóa trong llm_extractor
# Re-export để không break code nào còn import trực tiếp
from agents.intent.llm_extractor import _plan_from_tool_call as plan_from_tool_call
from agents.intent.llm_extractor import _parse_tool_arguments as parse_tool_arguments

__all__ = [
    "TOOL_GET_PATIENT", "TOOL_SEARCH_PATIENTS", "TOOL_GET_OBSERVATIONS",
    "TOOL_GET_ENCOUNTERS", "TOOL_GET_CONDITIONS", "TOOL_GET_MEDICATIONS",
    "TOOL_UNSUPPORTED", "FHIR_PROTECTED_TOOLS", "TOOL_TO_INTENT",
    "MEDICATION_KEYWORDS", "OBSERVATION_KEYWORDS", "ENCOUNTER_KEYWORDS",
    "PATIENT_CONTACT_KEYWORDS", "PATIENT_LIST_KEYWORDS", "CONDITION_KEYWORDS",
    "PATIENT_INFO_KEYWORDS",
    "IntentPlan", "IntentExtractor",
    "normalize_text", "contains_any", "string_or_none", "clamp",
    "extract_phone", "normalize_phone", "extract_birth_date", "normalize_birth_date",
    "extract_identifier", "extract_patient_name", "clean_name_candidate", "normalize_patient_id",
    "resolve_patient_id_for_request", "resolve_explicit_patient_id", "resolve_patient_id",
    "extract_patient_search_criteria", "has_patient_search_criteria", "is_patient_list_request",
    "is_self_patient_reference",
    "infer_observation_type",
    "enforce_contact_detail_routing", "enforce_patient_list_routing", "apply_all_patient_scope",
    "apply_patient_id_hint", "apply_patient_search_criteria_hint", "add_observation_type_hint",
    "FHIR_TOOL_DEFINITIONS",
    "RuleBasedIntentExtractor", "LLMIntentExtractor",
    "get_intent_extractor", "plan_from_tool_call", "parse_tool_arguments",
]
