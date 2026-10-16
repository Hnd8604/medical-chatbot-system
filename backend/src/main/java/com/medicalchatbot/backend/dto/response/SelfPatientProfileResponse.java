package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * FHIR record linked to the signed-in account, in one call. The FHIR payloads are passed through
 * from chatbot-service unchanged.
 */
public record SelfPatientProfileResponse(
        String patientId,
        JsonNode patient,
        JsonNode encounters,
        JsonNode observations,
        JsonNode conditions,
        JsonNode medications
) {
}
