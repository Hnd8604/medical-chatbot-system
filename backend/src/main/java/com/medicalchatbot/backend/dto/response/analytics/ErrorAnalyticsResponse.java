package com.medicalchatbot.backend.dto.response.analytics;

public record ErrorAnalyticsResponse(String date, String service, String errorType, long count) {
}
