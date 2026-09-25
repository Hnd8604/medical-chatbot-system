package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalchatbot.backend.entity.UserPatientLink;

public record AdminUserPatientLinkResponse(
        @JsonProperty("fhir_patient_id")
        String fhirPatientId,
        String relationship,
        @JsonProperty("is_primary")
        boolean primary
) {
    public static AdminUserPatientLinkResponse from(UserPatientLink link) {
        return new AdminUserPatientLinkResponse(
                link.getFhirPatientId(),
                link.getRelationship(),
                link.isPrimaryLink()
        );
    }
}
