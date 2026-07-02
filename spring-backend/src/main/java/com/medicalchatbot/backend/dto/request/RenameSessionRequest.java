package com.medicalchatbot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameSessionRequest(
        @NotBlank @Size(max = 255) String title
) {
}
