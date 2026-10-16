package com.medicalchatbot.backend.controller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.analytics.CacheMetricsResponse;
import com.medicalchatbot.backend.dto.response.analytics.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.analytics.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.analytics.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.analytics.RequestCountResponse;
import com.medicalchatbot.backend.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Date ranges are inclusive, default to the last 7 days and span at most 90 days. */
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Admin - Analytics", description = "Usage statistics of the assistant (last 7 days by default, at most 90)")
public class AdminAnalyticsController {

    AnalyticsService analyticsService;

    @GetMapping("/intents")
    @Operation(summary = "Most frequent intents per day")
    public ApiResponse<List<IntentAnalyticsResponse>> intents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit
    ) {
        return ApiResponse.<List<IntentAnalyticsResponse>>builder()
                .result(analyticsService.intents(from, to, limit))
                .message(ResponseMessage.GET_INTENT_ANALYTICS_SUCCESS)
                .build();
    }

    @GetMapping("/errors")
    @Operation(summary = "Most frequent errors per day")
    public ApiResponse<List<ErrorAnalyticsResponse>> errors(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit
    ) {
        return ApiResponse.<List<ErrorAnalyticsResponse>>builder()
                .result(analyticsService.errors(from, to, limit))
                .message(ResponseMessage.GET_ERROR_ANALYTICS_SUCCESS)
                .build();
    }

    @GetMapping("/performance")
    @Operation(summary = "Chat latency per model", description = "Average, p95 and p99 latency in milliseconds.")
    public ApiResponse<List<PerformanceAnalyticsResponse>> performance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit
    ) {
        return ApiResponse.<List<PerformanceAnalyticsResponse>>builder()
                .result(analyticsService.performance(from, to, limit))
                .message(ResponseMessage.GET_PERFORMANCE_ANALYTICS_SUCCESS)
                .build();
    }

    @GetMapping("/requests")
    @Operation(summary = "Number of chat requests")
    public ApiResponse<RequestCountResponse> requests(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.<RequestCountResponse>builder()
                .result(analyticsService.requestCount(from, to))
                .message(ResponseMessage.GET_REQUEST_COUNT_SUCCESS)
                .build();
    }

    @GetMapping("/cache")
    @Operation(summary = "Semantic cache effectiveness", description = "Hit rate and savings; the last 30 days by default.")
    public ApiResponse<CacheMetricsResponse> cache(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return ApiResponse.<CacheMetricsResponse>builder()
                .result(analyticsService.cacheMetrics(from, to))
                .message(ResponseMessage.GET_CACHE_METRICS_SUCCESS)
                .build();
    }
}
