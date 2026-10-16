package com.medicalchatbot.backend.enums;

/**
 * Operational alerts the system can raise. Each type has a fixed source and severity, so callers
 * only describe what happened.
 */
public enum AlertType {
    /** chatbot-service answered with HTTP 5xx. */
    CHATBOT_SERVICE_5XX("AI_SERVICE", AlertSeverity.CRITICAL),
    /** chatbot-service could not be reached. */
    CHATBOT_UNAVAILABLE("AI_SERVICE", AlertSeverity.CRITICAL),
    /** chatbot-service answered HTTP 200 but every FHIR retrieval step failed. */
    CHATBOT_TOOL_ERROR("AI_SERVICE", AlertSeverity.CRITICAL),
    RATE_LIMIT_VIOLATION("SECURITY", AlertSeverity.WARNING),
    QUOTA_EXCEEDED("QUOTA_SYSTEM", AlertSeverity.WARNING),
    FATAL_ERROR_500("SYSTEM_CORE", AlertSeverity.CRITICAL);

    private final String source;
    private final AlertSeverity severity;

    AlertType(String source, AlertSeverity severity) {
        this.source = source;
        this.severity = severity;
    }

    public String source() {
        return source;
    }

    public AlertSeverity severity() {
        return severity;
    }
}
