package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.service.BackupService;
import com.medicalchatbot.backend.service.CurrentUserService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/backup")
public class AdminBackupController {

    private final BackupService backupService;
    private final CurrentUserService currentUserService;

    /** Kích hoạt sao lưu thủ công. Trả 202 kèm bản ghi đang chạy để frontend poll. */
    @PostMapping
    public ResponseEntity<BackupHistory> triggerBackup() {
        String username = currentUserService.getCurrentUsername();
        BackupHistory row = backupService.triggerManualBackup(username);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(row);
    }

    @GetMapping("/history")
    public Page<BackupHistory> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return backupService.getHistory(PageRequest.of(page, size));
    }

    /** Khôi phục CSDL từ một bản backup. Trả 202 kèm bản ghi restore đang chạy. */
    @PostMapping("/{backupId}/restore")
    public ResponseEntity<RestoreHistory> restore(@PathVariable UUID backupId) {
        String username = currentUserService.getCurrentUsername();
        RestoreHistory row = backupService.triggerRestore(backupId, username);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(row);
    }

    @GetMapping("/restore-history")
    public Page<RestoreHistory> restoreHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return backupService.getRestoreHistory(PageRequest.of(page, size));
    }
}
