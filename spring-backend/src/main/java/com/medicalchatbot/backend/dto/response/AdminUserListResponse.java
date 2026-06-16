package com.medicalchatbot.backend.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminUserListResponse(
        int page,
        int size,
        @JsonProperty("total_elements")
        long totalElements,
        @JsonProperty("total_pages")
        int totalPages,
        List<AdminUserItemResponse> users
) {
}
