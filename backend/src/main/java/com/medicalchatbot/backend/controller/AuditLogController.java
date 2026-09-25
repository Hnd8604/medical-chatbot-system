package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.AuditLogResponse;
import com.medicalchatbot.backend.service.AuditLogService;
import com.medicalchatbot.backend.utils.PageRequestFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {
    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "action", "resourceType", "resourceId", "createdAt"
    );

    private final AuditLogService auditLogService;


    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toDate,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        var pageRequest = PageRequestFactory.create(
                page, size, sort, "createdAt", Sort.Direction.DESC, SORT_FIELDS
        );
        Page<AuditLogResponse> logs = auditLogService.searchAuditLogs(
                userId, action, resourceType, resourceId, fromDate, toDate, pageRequest
        );

        return ResponseEntity.ok(logs);
    }
}
