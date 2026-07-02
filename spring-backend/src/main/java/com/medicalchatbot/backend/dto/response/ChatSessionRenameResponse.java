package com.medicalchatbot.backend.dto.response;

import java.util.UUID;

public record ChatSessionRenameResponse(
        UUID id,
        String title
) {
}
