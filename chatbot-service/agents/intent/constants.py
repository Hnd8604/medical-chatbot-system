DEFAULT_PATIENT_ID = "demo-patient-001"

TOOL_GET_PATIENT = "get_patient_by_id"
TOOL_SEARCH_PATIENTS = "search_patients"
TOOL_GET_OBSERVATIONS = "get_observations"
TOOL_GET_ENCOUNTERS = "get_encounters"
TOOL_GET_CONDITIONS = "get_conditions"
TOOL_GET_MEDICATIONS = "get_medication_requests"
TOOL_UNSUPPORTED = "unsupported_question"

TOOL_TO_INTENT = {
    TOOL_GET_PATIENT: "patient",
    TOOL_SEARCH_PATIENTS: "patients",
    TOOL_GET_OBSERVATIONS: "observations",
    TOOL_GET_ENCOUNTERS: "encounters",
    TOOL_GET_CONDITIONS: "conditions",
    TOOL_GET_MEDICATIONS: "medications",
    TOOL_UNSUPPORTED: "unknown",
}
