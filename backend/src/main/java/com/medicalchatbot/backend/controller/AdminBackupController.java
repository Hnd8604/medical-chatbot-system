package com.medicalchatbot.backend.controller;

import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.BackupResponse;
import com.medicalchatbot.backend.dto.response.admin.RestoreResponse;
import com.medicalchatbot.backend.service.BackupService;
import com.medicalchatbot.backend.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Admin - Backup", description = "Database backups and restores. Jobs run in the background: poll the history.")
public class AdminBackupController {

    BackupService backupService;

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a backup", description = "Answers 202 with the RUNNING job; only one job runs at a time.")
    public ApiResponse<BackupResponse> startBackup() {
        return ApiResponse.<BackupResponse>builder()
                .result(backupService.startManualBackup(SecurityUtils.currentUsername()))
                .message(ResponseMessage.START_BACKUP_SUCCESS)
                .build();
    }

    @GetMapping("/backups")
    @Operation(summary = "Backup history", description = "Newest first.")
    public ApiResponse<PageResponse<BackupResponse>> backups(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.<PageResponse<BackupResponse>>builder()
                .result(backupService.backups(PageRequest.of(page, size)))
                .message(ResponseMessage.GET_BACKUPS_SUCCESS)
                .build();
    }

    @PostMapping("/backups/{backupId}/restore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Restore a backup", description = "Overwrites the current databases with the backup.")
    public ApiResponse<RestoreResponse> startRestore(@PathVariable UUID backupId) {
        return ApiResponse.<RestoreResponse>builder()
                .result(backupService.startRestore(backupId, SecurityUtils.currentUsername()))
                .message(ResponseMessage.START_RESTORE_SUCCESS)
                .build();
    }

    @GetMapping("/restores")
    @Operation(summary = "Restore history", description = "Newest first.")
    public ApiResponse<PageResponse<RestoreResponse>> restores(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.<PageResponse<RestoreResponse>>builder()
                .result(backupService.restores(PageRequest.of(page, size)))
                .message(ResponseMessage.GET_RESTORES_SUCCESS)
                .build();
    }
}
