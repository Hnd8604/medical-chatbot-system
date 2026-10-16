package com.medicalchatbot.backend.controller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.medicalchatbot.backend.dto.response.CacheMetricsResponse;
import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.RequestCountResponse;
import com.medicalchatbot.backend.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Date ranges are inclusive, default to the last 7 days and span at most 90 days. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/analytics")
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/intents")
    public List<IntentAnalyticsResponse> intents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return analyticsService.intents(from, to, limit);
    }

    @GetMapping("/errors")
    public List<ErrorAnalyticsResponse> errors(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return analyticsService.errors(from, to, limit);
    }

    @GetMapping("/performance")
    public List<PerformanceAnalyticsResponse> performance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return analyticsService.performance(from, to, limit);
    }

    @GetMapping("/requests")
    public RequestCountResponse requests(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return analyticsService.requestCount(from, to);
    }

    /** Semantic-cache hit rate and savings; the last 30 days by default. */
    @GetMapping("/cache")
    public CacheMetricsResponse cache(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return analyticsService.cacheMetrics(from, to);
    }
}
