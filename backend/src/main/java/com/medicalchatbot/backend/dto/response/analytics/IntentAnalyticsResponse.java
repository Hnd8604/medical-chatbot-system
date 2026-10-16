package com.medicalchatbot.backend.dto.response.analytics;

public record IntentAnalyticsResponse(String date, String intent, long count) {
}
