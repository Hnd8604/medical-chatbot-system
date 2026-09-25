package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.integration.backup.BackupJobRunner;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final BackupHistoryRepository backupHistoryRepository;
    private final RestoreHistoryRepository restoreHistoryRepository;
    private final BackupJobRunner backupJobRunner;

    @Value("${backup.enabled:true}")
    private boolean enabled;

    /**
     * Tạo bản ghi RUNNING rồi chạy script backup ở luồng nền. Trả về ngay bản ghi
     * đang chạy để frontend poll trạng thái qua {@link #getHistory}.
     */
    @Transactional
    public BackupHistory triggerManualBackup(String username) {
        if (!enabled) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Chức năng sao lưu đang bị tắt (backup.enabled=false).");
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Đang có một tiến trình sao lưu chạy, vui lòng đợi hoàn tất.");
        }
        return startBackup(BackupTrigger.MANUAL, username, "manual");
    }

    /**
     * Sao lưu tự động theo lịch. Backend tự xử lý (không cần Windows Task Scheduler).
     * Mặc định 02:00 hằng ngày; đổi qua {@code backup.cron} / {@code backup.timezone}.
     */
    @Scheduled(cron = "${backup.cron:0 0 2 * * *}", zone = "${backup.timezone:Asia/Ho_Chi_Minh}")
    public void scheduledBackup() {
        if (!enabled) {
            log.debug("Bỏ qua backup theo lịch: backup.enabled=false.");
            return;
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            log.warn("Bỏ qua backup theo lịch: đang có một tiến trình sao lưu chạy.");
            return;
        }
        log.info("Bắt đầu sao lưu tự động theo lịch...");
        startBackup(BackupTrigger.AUTO, null, "auto");
    }

    /** Tạo bản ghi RUNNING và chạy script ở luồng nền. */
    private BackupHistory startBackup(BackupTrigger trigger, String username, String triggerArg) {
        BackupHistory row = backupHistoryRepository.save(BackupHistory.builder()
                .triggerType(trigger)
                .status(BackupStatus.RUNNING)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());

        UUID id = row.getId();
        CompletableFuture.runAsync(() -> backupJobRunner.runBackup(id, triggerArg));
        return row;
    }

    @Transactional(readOnly = true)
    public Page<BackupHistory> getHistory(Pageable pageable) {
        return backupHistoryRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    // ===================== KHÔI PHỤC (RESTORE) =====================

    /**
     * Khôi phục CSDL từ một bản backup đã có. Tạo bản ghi RUNNING rồi chạy
     * restore-auto.sh ở luồng nền. CẨN THẬN: ghi đè dữ liệu hiện tại.
     */
    @Transactional
    public RestoreHistory triggerRestore(UUID backupId, String username) {
        if (!enabled) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Chức năng sao lưu/khôi phục đang bị tắt (backup.enabled=false).");
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)
                || restoreHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Đang có một tiến trình sao lưu/khôi phục chạy, vui lòng đợi hoàn tất.");
        }

        BackupHistory backup = backupHistoryRepository.findById(backupId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy bản sao lưu với ID: " + backupId));
        if (backup.getStatus() != BackupStatus.SUCCESS && backup.getStatus() != BackupStatus.PARTIAL) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT,
                    "Bản sao lưu này không có dữ liệu hợp lệ để khôi phục.");
        }

        String appFile = "";
        String hapiFile = "";
        JsonNode items = backup.getItemsJson();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (!item.path("ok").asBoolean(false)) {
                    continue;
                }
                String db = item.path("db").asText("");
                String file = item.path("file").asText("");
                if ("app".equals(db)) {
                    appFile = file;
                } else if ("hapi".equals(db)) {
                    hapiFile = file;
                }
            }
        }
        if (appFile.isEmpty() && hapiFile.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT,
                    "Bản sao lưu không có file nào để khôi phục.");
        }

        RestoreHistory row = restoreHistoryRepository.save(RestoreHistory.builder()
                .backupId(backupId)
                .status(BackupStatus.RUNNING)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());

        UUID id = row.getId();
        OffsetDateTime startedAt = row.getStartedAt();
        String appArg = appFile;
        String hapiArg = hapiFile;
        CompletableFuture.runAsync(
                () -> backupJobRunner.runRestore(id, backupId, username, startedAt, appArg, hapiArg)
        );
        return row;
    }

    @Transactional(readOnly = true)
    public Page<RestoreHistory> getRestoreHistory(Pageable pageable) {
        return restoreHistoryRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

}
