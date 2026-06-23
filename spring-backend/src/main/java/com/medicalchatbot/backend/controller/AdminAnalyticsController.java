package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/analytics")
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;

    public AdminAnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/intents")
    public List<IntentAnalyticsResponse> getIntentAnalytics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "7") int size
    ) {
        return analyticsService.getIntentAnalytics(page, size);
    }

    @GetMapping("/errors")
    public List<ErrorAnalyticsResponse> getErrorAnalytics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "7") int size
    ) {
        return analyticsService.getErrorAnalytics(page, size);
    }

    @GetMapping("/performance")
    public List<PerformanceAnalyticsResponse> getPerformanceAnalytics() {
        return analyticsService.getPerformanceAnalytics();
    }
}
