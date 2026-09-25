package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.AlertResponse;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.utils.PageRequestFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/admin/alerts")
@RequiredArgsConstructor
public class AdminAlertController {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "source", "alertType", "severity", "status", "createdAt", "resolvedAt"
    );

    private final AlertService alertService;
    private final CurrentUserService currentUserService;


    @GetMapping
    public ResponseEntity<Page<AlertResponse>> getAlerts(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toDate,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        var pageRequest = PageRequestFactory.create(
                page, size, sort, "createdAt", Sort.Direction.DESC, SORT_FIELDS
        );

        return ResponseEntity.ok(alertService.searchAlerts(
                status, severity, source, alertType, fromDate, toDate, pageRequest
        ));
    }

    @PatchMapping("/{id}/resolve")
    public ResponseEntity<Void> resolveAlert(@PathVariable UUID id) {
        alertService.resolveAlert(id, currentUserService.getCurrentUsername());
        return ResponseEntity.ok().build();
    }
}
