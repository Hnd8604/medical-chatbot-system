package com.medicalchatbot.backend.dto.response.chat;

import java.util.UUID;

public record ChatSessionRenameResponse(UUID id, String title) {
}
