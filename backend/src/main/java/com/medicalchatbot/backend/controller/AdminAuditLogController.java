package com.medicalchatbot.backend.controller;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.AuditLogFiltersResponse;
import com.medicalchatbot.backend.dto.response.AuditLogResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.service.AuditService;
import com.medicalchatbot.backend.util.PageRequestFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditLogController {

    private static final Set<String> SORT_FIELDS = Set.of("action", "resourceType", "resourceId", "createdAt");

    private final AuditService auditService;

    @GetMapping
    public PageResponse<AuditLogResponse> search(
            @RequestParam(name = "user_id", required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) String resourceId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        return auditService.search(userId, action, resourceType, resourceId, from, to,
                PageRequestFactory.create(page, size, sort, "createdAt", Sort.Direction.DESC, SORT_FIELDS));
    }

    /** Actions and resource types the log can be filtered by. */
    @GetMapping("/filters")
    public AuditLogFiltersResponse filters() {
        return auditService.filters();
    }
}
