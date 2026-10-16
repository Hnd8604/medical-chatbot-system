package com.medicalchatbot.backend.controller;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.AuditLogFiltersResponse;
import com.medicalchatbot.backend.dto.response.admin.AuditLogResponse;
import com.medicalchatbot.backend.service.AuditService;
import com.medicalchatbot.backend.util.PageRequestFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Admin - Audit log", description = "Security- and PHI-relevant actions")
public class AdminAuditLogController {

    private static final Set<String> SORT_FIELDS = Set.of("action", "resourceType", "resourceId", "createdAt");

    AuditService auditService;

    @GetMapping
    @Operation(summary = "Search the audit log", description = "Filters are optional; newest first by default.")
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        return ApiResponse.<PageResponse<AuditLogResponse>>builder()
                .result(auditService.search(userId, action, resourceType, resourceId, from, to,
                        PageRequestFactory.create(page, size, sort, "createdAt", Sort.Direction.DESC, SORT_FIELDS)))
                .message(ResponseMessage.GET_AUDIT_LOGS_SUCCESS)
                .build();
    }

    @GetMapping("/filters")
    @Operation(summary = "Audit log filters", description = "Actions and resource types the log can be filtered by.")
    public ApiResponse<AuditLogFiltersResponse> filters() {
        return ApiResponse.<AuditLogFiltersResponse>builder()
                .result(auditService.filters())
                .message(ResponseMessage.GET_AUDIT_LOG_FILTERS_SUCCESS)
                .build();
    }
}
