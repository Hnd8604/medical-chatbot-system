package com.medicalchatbot.backend.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.UpdateUserRoleRequest;
import com.medicalchatbot.backend.dto.request.UpdateUserStatusRequest;
import com.medicalchatbot.backend.dto.response.AdminUserResponse;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.service.AdminUserService;
import com.medicalchatbot.backend.service.CostService;
import com.medicalchatbot.backend.service.QuotaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final QuotaService quotaService;
    private final CostService costService;

    /** {@code search} matches username, email or display name. */
    @GetMapping
    public PageResponse<AdminUserResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search
    ) {
        return adminUserService.list(page, size, search);
    }

    /** An admin cannot lock or disable their own account. */
    @PatchMapping("/{userId}/status")
    public AdminUserResponse updateStatus(@PathVariable UUID userId, @Valid @RequestBody UpdateUserStatusRequest request) {
        return adminUserService.updateStatus(userId, request.status());
    }

    /** An admin cannot demote themselves. */
    @PatchMapping("/{userId}/role")
    public AdminUserResponse updateRole(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRoleRequest request) {
        return adminUserService.updateRole(userId, request.role());
    }

    @GetMapping("/{userId}/quota")
    public QuotaStatusResponse quota(@PathVariable UUID userId) {
        return quotaService.userStatus(userId);
    }

    @GetMapping("/{userId}/costs")
    public CostSummaryResponse costs(
            @PathVariable UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return costService.userSummary(userId, from, to);
    }
}
