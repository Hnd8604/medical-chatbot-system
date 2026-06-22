package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.service.CostManagementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/costs")
public class AdminCostController {

    private final CostManagementService costManagementService;

    public AdminCostController(CostManagementService costManagementService) {
        this.costManagementService = costManagementService;
    }


    @GetMapping("/users/{username}")
    public CostSummaryResponse getUserCostSummary(
            @PathVariable String username,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return costManagementService.getUserCostSummaryByUsername(username, from, to);
    }


    @GetMapping("/pricing")
    public ModelPricingListResponse getActivePricing() {
        return costManagementService.activePricing();
    }
}