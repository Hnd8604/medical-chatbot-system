package com.medicalchatbot.backend.dto.response;

public record ErrorAnalyticsResponse(String date, String service, String errorType, long count) {
}
