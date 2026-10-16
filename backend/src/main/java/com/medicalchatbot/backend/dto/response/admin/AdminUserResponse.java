package com.medicalchatbot.backend.dto.response.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.enums.PatientRelationship;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;

public record AdminUserResponse(
        UUID id,
        String username,
        String email,
        String displayName,
        UserRole role,
        UserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<PatientLink> patientLinks
) {

    public record PatientLink(
            String fhirPatientId,
            PatientRelationship relationship,
            boolean isPrimary
    ) {
    }
}
