package com.medicalchatbot.backend.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.config.properties.BackupProperties;
import com.medicalchatbot.backend.dto.response.BackupResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.RestoreResponse;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.BackupMapper;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database backups (app DB + HAPI FHIR DB) and restores. Jobs are recorded as RUNNING and the
 * scripts run in the background; the admin UI polls the history until they finish.
 */
@Slf4j
@Service
public class BackupService {

    private final BackupHistoryRepository backupHistoryRepository;
    private final RestoreHistoryRepository restoreHistoryRepository;
    private final BackupJobRunner backupJobRunner;
    private final BackupMapper backupMapper;
    private final BackupProperties properties;
    private final TaskExecutor taskExecutor;

    public BackupService(
            BackupHistoryRepository backupHistoryRepository,
            RestoreHistoryRepository restoreHistoryRepository,
            BackupJobRunner backupJobRunner,
            BackupMapper backupMapper,
            BackupProperties properties,
            @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME) TaskExecutor taskExecutor
    ) {
        this.backupHistoryRepository = backupHistoryRepository;
        this.restoreHistoryRepository = restoreHistoryRepository;
        this.backupJobRunner = backupJobRunner;
        this.backupMapper = backupMapper;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    public BackupResponse startManualBackup(String username) {
        requireEnabled();
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw AppException.conflict("Đang có một tiến trình sao lưu chạy, vui lòng đợi hoàn tất.");
        }
        return backupMapper.toResponse(startBackup(BackupTrigger.MANUAL, username));
    }

    /** Daily automatic backup ({@code backup.cron}, 02:00 by default) run by the backend itself. */
    @Scheduled(cron = "${backup.cron:0 0 2 * * *}", zone = "${backup.timezone:Asia/Ho_Chi_Minh}")
    public void scheduledBackup() {
        if (!properties.enabled()) {
            log.debug("Skipping scheduled backup: backup.enabled=false");
            return;
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            log.warn("Skipping scheduled backup: another backup is still running");
            return;
        }
        log.info("Starting scheduled backup");
        startBackup(BackupTrigger.AUTO, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<BackupResponse> backups(Pageable pageable) {
        return PageResponse.of(backupHistoryRepository.findAllByOrderByCreatedAtDesc(pageable), backupMapper::toResponse);
    }

    /** Overwrites both databases with the chosen backup. Only one backup/restore job runs at a time. */
    public RestoreResponse startRestore(UUID backupId, String username) {
        requireEnabled();
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)
                || restoreHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw AppException.conflict("Đang có một tiến trình sao lưu/khôi phục chạy, vui lòng đợi hoàn tất.");
        }
        BackupHistory backup = backupHistoryRepository.findById(backupId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy bản sao lưu."));
        if (!backup.isRestorable()) {
            throw AppException.invalid("Bản sao lưu này không có dữ liệu hợp lệ để khôi phục.");
        }
        DumpFiles files = DumpFiles.of(backup.getItemsJson());
        if (files.isEmpty()) {
            throw AppException.invalid("Bản sao lưu không có file nào để khôi phục.");
        }

        RestoreHistory restore = restoreHistoryRepository.save(RestoreHistory.builder()
                .backupId(backupId)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());
        RestoreJob job = new RestoreJob(restore.getId(), backupId, username, restore.getStartedAt(), files.app(), files.hapi());
        taskExecutor.execute(() -> backupJobRunner.runRestore(job));
        return backupMapper.toResponse(restore);
    }

    @Transactional(readOnly = true)
    public PageResponse<RestoreResponse> restores(Pageable pageable) {
        return PageResponse.of(restoreHistoryRepository.findAllByOrderByCreatedAtDesc(pageable), backupMapper::toResponse);
    }

    /** The RUNNING row is committed before the job starts, so the job always finds it. */
    private BackupHistory startBackup(BackupTrigger trigger, String username) {
        BackupHistory backup = backupHistoryRepository.save(BackupHistory.builder()
                .triggerType(trigger)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());
        UUID backupId = backup.getId();
        taskExecutor.execute(() -> backupJobRunner.runBackup(backupId, trigger));
        return backup;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "Chức năng sao lưu đang bị tắt (backup.enabled=false).");
        }
    }

    /** Everything a restore job needs, captured before the app DB is overwritten. */
    public record RestoreJob(
            UUID restoreId,
            UUID backupId,
            String triggeredBy,
            OffsetDateTime startedAt,
            String appDumpFile,
            String hapiDumpFile
    ) {
    }

    /** Dump files of the databases that were backed up successfully. */
    private record DumpFiles(String app, String hapi) {

        static DumpFiles of(JsonNode items) {
            String app = "";
            String hapi = "";
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    if (!item.path("ok").asBoolean(false)) {
                        continue;
                    }
                    String file = item.path("file").asText("");
                    switch (item.path("db").asText("")) {
                        case "app" -> app = file;
                        case "hapi" -> hapi = file;
                        default -> {
                        }
                    }
                }
            }
            return new DumpFiles(app, hapi);
        }

        boolean isEmpty() {
            return app.isEmpty() && hapi.isEmpty();
        }
    }
}
