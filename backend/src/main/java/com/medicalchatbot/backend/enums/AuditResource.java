package com.medicalchatbot.backend.enums;

/** Resource types written to {@code audit_logs.resource_type}. */
public enum AuditResource implements PersistedEnum {
    APP_USER("app_user"),
    AUTH("auth"),
    PATIENT_LINK("app_user_patient_link"),
    CHAT_SESSION("chat_session"),
    QUOTA_POLICY("quota_policy"),
    EXPORT_SESSION("SESSION"),
    EXPORT_DATE_RANGE("DATE_RANGE"),
    CACHE("Cache"),
    PATIENT("Patient"),
    OBSERVATION("Observation"),
    CONDITION("Condition"),
    ENCOUNTER("Encounter"),
    MEDICATION_REQUEST("MedicationRequest"),
    FHIR_RESOURCE("FhirResource");

    private final String value;

    AuditResource(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
