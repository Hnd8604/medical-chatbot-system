package com.medicalchatbot.backend.controller;

import java.time.LocalDate;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.usage.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import com.medicalchatbot.backend.service.CostService;
import com.medicalchatbot.backend.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/usage")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Usage", description = "Daily quota and AI cost of the signed-in user")
public class UsageController {

    QuotaService quotaService;
    CostService costService;

    @GetMapping("/quota")
    @Operation(summary = "Get my quota", description = "Today's usage against the daily limits of my quota policy.")
    public ApiResponse<QuotaStatusResponse> quota() {
        return ApiResponse.<QuotaStatusResponse>builder()
                .result(quotaService.currentUserStatus())
                .message(ResponseMessage.GET_QUOTA_SUCCESS)
                .build();
    }

    @GetMapping("/costs")
    @Operation(summary = "Get my AI cost", description = "Usage and cost between two dates (inclusive), by model and by day.")
    public ApiResponse<CostSummaryResponse> costs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.<CostSummaryResponse>builder()
                .result(costService.currentUserSummary(from, to))
                .message(ResponseMessage.GET_COSTS_SUCCESS)
                .build();
    }
}
