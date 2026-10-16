package com.medicalchatbot.backend.controller;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.AlertResponse;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.util.PageRequestFactory;
import com.medicalchatbot.backend.util.SecurityUtils;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/alerts")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Admin - Alerts", description = "Operational alerts raised by the system")
public class AdminAlertController {

    private static final Set<String> SORT_FIELDS = Set.of(
            "source", "alertType", "severity", "status", "createdAt", "resolvedAt"
    );

    AlertService alertService;

    @GetMapping
    @Operation(summary = "Search alerts",
            description = "Filters are optional. sort is field,direction with a camelCase field, e.g. createdAt,desc.")
    public ApiResponse<PageResponse<AlertResponse>> search(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String alertType,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        return ApiResponse.<PageResponse<AlertResponse>>builder()
                .result(alertService.search(status, severity, source, alertType, from, to,
                        PageRequestFactory.create(page, size, sort, "createdAt", Sort.Direction.DESC, SORT_FIELDS)))
                .message(ResponseMessage.GET_ALERTS_SUCCESS)
                .build();
    }

    @PutMapping("/{id}/resolve")
    @Operation(summary = "Resolve an alert", description = "Resolving an already resolved alert keeps the first resolution.")
    public ApiResponse<Void> resolve(@PathVariable UUID id) {
        alertService.resolve(id, SecurityUtils.currentUsername());
        return ApiResponse.<Void>builder().message(ResponseMessage.RESOLVE_ALERT_SUCCESS).build();
    }
}
