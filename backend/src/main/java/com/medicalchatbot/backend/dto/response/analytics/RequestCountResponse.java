package com.medicalchatbot.backend.dto.response.analytics;

import java.time.LocalDate;

public record RequestCountResponse(LocalDate from, LocalDate to, long requestCount) {
}
