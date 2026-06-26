package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.CacheMetricsResponse;
import com.medicalchatbot.backend.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping("/cache")
    public ResponseEntity<CacheMetricsResponse> getCacheMetrics(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate
    ) {

        if (startDate != null && endDate != null) {
            return ResponseEntity.ok(metricsService.getCacheMetrics(startDate, endDate));
        }


        return ResponseEntity.ok(metricsService.getCacheMetrics());
    }
}