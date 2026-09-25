package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;

public record AdminUserItemResponse(
        UUID id,
        String username,
        String email,
        @JsonProperty("display_name")
        String displayName,
        UserRole role,
        UserStatus status,
        @JsonProperty("created_at")
        OffsetDateTime createdAt,
        @JsonProperty("updated_at")
        OffsetDateTime updatedAt,
        @JsonProperty("patient_links")
        List<AdminUserPatientLinkResponse> patientLinks
) {
    public static AdminUserItemResponse from(User user) {
        return from(user, List.of());
    }

    public static AdminUserItemResponse from(User user, List<UserPatientLink> links) {
        return new AdminUserItemResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                (links == null ? List.<UserPatientLink>of() : links).stream()
                        .map(AdminUserPatientLinkResponse::from)
                        .toList()
        );
    }
}
