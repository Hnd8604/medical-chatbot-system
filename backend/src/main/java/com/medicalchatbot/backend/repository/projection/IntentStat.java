package com.medicalchatbot.backend.repository.projection;

/** Number of chat turns classified as {@code intent} on {@code date} (ISO yyyy-MM-dd). */
public record IntentStat(String date, String intent, long count) {
}
