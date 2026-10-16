package com.medicalchatbot.backend.controller;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.AlertResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.util.PageRequestFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/alerts")
public class AdminAlertController {

    private static final Set<String> SORT_FIELDS = Set.of(
            "source", "alertType", "severity", "status", "createdAt", "resolvedAt"
    );

    private final AlertService alertService;
    private final CurrentUserService currentUserService;

    /** {@code sort} is {@code field,direction} with a camelCase field, e.g. {@code createdAt,desc}. */
    @GetMapping
    public PageResponse<AlertResponse> search(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) String source,
            @RequestParam(name = "alert_type", required = false) String alertType,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        return alertService.search(status, severity, source, alertType, from, to,
                PageRequestFactory.create(page, size, sort, "createdAt", Sort.Direction.DESC, SORT_FIELDS));
    }

    @PatchMapping("/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable UUID id) {
        alertService.resolve(id, currentUserService.requireCurrentUsername());
    }
}
