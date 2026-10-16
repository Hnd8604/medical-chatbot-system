package com.medicalchatbot.backend.dto.response;

import java.time.LocalDate;

public record RequestCountResponse(LocalDate from, LocalDate to, long requestCount) {
}
