package com.medicalchatbot.backend.dto.response;

import java.util.List;

public record ChatSessionListResponse(
        List<ChatSessionSummary> sessions
) {
}
