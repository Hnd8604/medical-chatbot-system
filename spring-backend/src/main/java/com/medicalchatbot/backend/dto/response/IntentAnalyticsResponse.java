package com.medicalchatbot.backend.dto.response;

public record IntentAnalyticsResponse(String date, String intent, long count) {
}
