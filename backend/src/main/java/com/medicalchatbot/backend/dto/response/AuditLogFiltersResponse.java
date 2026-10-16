package com.medicalchatbot.backend.dto.response;

import java.util.List;

/** Values the audit log can be filtered by, so the admin UI does not hard-code them. */
public record AuditLogFiltersResponse(List<String> actions, List<String> resourceTypes) {
}
