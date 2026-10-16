package com.medicalchatbot.backend.controller;

import java.time.LocalDate;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.service.CostService;
import com.medicalchatbot.backend.service.QuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quota and AI cost of the signed-in user. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/usage")
public class UsageController {

    private final QuotaService quotaService;
    private final CostService costService;

    @GetMapping("/quota")
    public QuotaStatusResponse quota() {
        return quotaService.currentUserStatus();
    }

    @GetMapping("/costs")
    public CostSummaryResponse costs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return costService.currentUserSummary(from, to);
    }
}
