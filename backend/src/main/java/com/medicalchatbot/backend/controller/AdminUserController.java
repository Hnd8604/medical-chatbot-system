package com.medicalchatbot.backend.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.admin.UpdateUserRoleRequest;
import com.medicalchatbot.backend.dto.request.admin.UpdateUserStatusRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.AdminUserResponse;
import com.medicalchatbot.backend.dto.response.usage.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import com.medicalchatbot.backend.service.AdminUserService;
import com.medicalchatbot.backend.service.CostService;
import com.medicalchatbot.backend.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Admin - Users", description = "Accounts, their role and status, quota and AI cost")
public class AdminUserController {

    AdminUserService adminUserService;
    QuotaService quotaService;
    CostService costService;

    @GetMapping
    @Operation(summary = "List users", description = "Newest first; search matches username, email or display name.")
    public ApiResponse<PageResponse<AdminUserResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search
    ) {
        return ApiResponse.<PageResponse<AdminUserResponse>>builder()
                .result(adminUserService.list(page, size, search))
                .message(ResponseMessage.GET_USERS_SUCCESS)
                .build();
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Change a user's status", description = "An admin cannot lock or disable their own account.")
    public ApiResponse<AdminUserResponse> updateStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return ApiResponse.<AdminUserResponse>builder()
                .result(adminUserService.updateStatus(userId, request.status()))
                .message(ResponseMessage.UPDATE_USER_STATUS_SUCCESS)
                .build();
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "Change a user's role", description = "An admin cannot demote themselves.")
    public ApiResponse<AdminUserResponse> updateRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        return ApiResponse.<AdminUserResponse>builder()
                .result(adminUserService.updateRole(userId, request.role()))
                .message(ResponseMessage.UPDATE_USER_ROLE_SUCCESS)
                .build();
    }

    @GetMapping("/{userId}/quota")
    @Operation(summary = "Get a user's quota", description = "Today's usage against the user's daily limits.")
    public ApiResponse<QuotaStatusResponse> quota(@PathVariable UUID userId) {
        return ApiResponse.<QuotaStatusResponse>builder()
                .result(quotaService.userStatus(userId))
                .message(ResponseMessage.GET_USER_QUOTA_SUCCESS)
                .build();
    }

    @GetMapping("/{userId}/costs")
    @Operation(summary = "Get a user's AI cost", description = "Between two dates (inclusive), by model and by day.")
    public ApiResponse<CostSummaryResponse> costs(
            @PathVariable UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.<CostSummaryResponse>builder()
                .result(costService.userSummary(userId, from, to))
                .message(ResponseMessage.GET_USER_COSTS_SUCCESS)
                .build();
    }
}
