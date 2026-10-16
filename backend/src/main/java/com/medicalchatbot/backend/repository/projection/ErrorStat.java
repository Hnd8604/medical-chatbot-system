package com.medicalchatbot.backend.repository.projection;

/** Number of alerts of {@code errorType} raised by {@code service} on {@code date} (ISO yyyy-MM-dd). */
public record ErrorStat(String date, String service, String errorType, long count) {
}
