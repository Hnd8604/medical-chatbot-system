package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalchatbot.backend.entity.User;
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
        OffsetDateTime updatedAt
) {
    public static AdminUserItemResponse from(User user) {
        return new AdminUserItemResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
