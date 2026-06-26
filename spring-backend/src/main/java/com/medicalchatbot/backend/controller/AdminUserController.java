package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.request.UpdateUserRoleRequest;
import com.medicalchatbot.backend.dto.request.UpdateUserStatusRequest;
import com.medicalchatbot.backend.dto.response.AdminUserItemResponse;
import com.medicalchatbot.backend.dto.response.AdminUserListResponse;
import com.medicalchatbot.backend.service.AdminUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public AdminUserListResponse listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return adminUserService.listUsers(page, size);
    }

    @PatchMapping("/{userId}/status")
    public AdminUserItemResponse updateStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return adminUserService.updateStatus(userId, request.status());
    }

    @PatchMapping("/{userId}/role")
    public AdminUserItemResponse updateRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        return adminUserService.updateRole(userId, request.role());
    }
}
