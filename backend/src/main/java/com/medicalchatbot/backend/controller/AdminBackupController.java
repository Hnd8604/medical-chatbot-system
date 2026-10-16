package com.medicalchatbot.backend.controller;

import java.util.UUID;

import com.medicalchatbot.backend.dto.response.BackupResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.RestoreResponse;
import com.medicalchatbot.backend.service.BackupService;
import com.medicalchatbot.backend.service.CurrentUserService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Backup and restore jobs run in the background: the POSTs answer 202 with the RUNNING job to poll. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminBackupController {

    private final BackupService backupService;
    private final CurrentUserService currentUserService;

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BackupResponse startBackup() {
        return backupService.startManualBackup(currentUserService.requireCurrentUsername());
    }

    @GetMapping("/backups")
    public PageResponse<BackupResponse> backups(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return backupService.backups(PageRequest.of(page, size));
    }

    /** Overwrites the current databases with the backup. */
    @PostMapping("/backups/{backupId}/restore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RestoreResponse startRestore(@PathVariable UUID backupId) {
        return backupService.startRestore(backupId, currentUserService.requireCurrentUsername());
    }

    @GetMapping("/restores")
    public PageResponse<RestoreResponse> restores(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return backupService.restores(PageRequest.of(page, size));
    }
}
