TOOL_FHIR_STATUS = "fhir_status"
TOOL_GET_PATIENT = "get_patient_by_id"
TOOL_GET_RESOURCE = "get_resource_by_id"
TOOL_SEARCH_PATIENTS = "search_patients"
TOOL_GET_OBSERVATIONS = "get_observations"
TOOL_GET_ENCOUNTERS = "get_encounters"
TOOL_GET_CONDITIONS = "get_conditions"
TOOL_GET_MEDICATIONS = "get_medication_requests"
TOOL_GET_ALL_OBSERVATIONS = "get_all_patient_observations"
TOOL_GET_ALL_ENCOUNTERS = "get_all_patient_encounters"
TOOL_GET_ALL_CONDITIONS = "get_all_patient_conditions"
TOOL_GET_ALL_MEDICATIONS = "get_all_patient_medication_requests"
TOOL_UNSUPPORTED = "unsupported_question"

# Các tool get_all_patient_* được chuẩn hoá về tool gốc + all_patients=True
# ngay khi dựng IntentPlan, để tái dùng toàn bộ dispatch/policy/cache hiện có.
ALL_PATIENT_TOOL_TO_BASE = {
    TOOL_GET_ALL_OBSERVATIONS: TOOL_GET_OBSERVATIONS,
    TOOL_GET_ALL_ENCOUNTERS: TOOL_GET_ENCOUNTERS,
    TOOL_GET_ALL_CONDITIONS: TOOL_GET_CONDITIONS,
    TOOL_GET_ALL_MEDICATIONS: TOOL_GET_MEDICATIONS,
}

FHIR_PROTECTED_TOOLS = {
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_MEDICATIONS,
    *ALL_PATIENT_TOOL_TO_BASE,
}

TOOL_TO_INTENT = {
    TOOL_FHIR_STATUS: "fhir_status",
    TOOL_GET_PATIENT: "patient",
    TOOL_GET_RESOURCE: "resource",
    TOOL_SEARCH_PATIENTS: "list_patients",
    TOOL_GET_OBSERVATIONS: "observations",
    TOOL_GET_ENCOUNTERS: "encounters",
    TOOL_GET_CONDITIONS: "conditions",
    TOOL_GET_MEDICATIONS: "medications",
    TOOL_GET_ALL_OBSERVATIONS: "observations",
    TOOL_GET_ALL_ENCOUNTERS: "encounters",
    TOOL_GET_ALL_CONDITIONS: "conditions",
    TOOL_GET_ALL_MEDICATIONS: "medications",
    TOOL_UNSUPPORTED: "unknown",
}
