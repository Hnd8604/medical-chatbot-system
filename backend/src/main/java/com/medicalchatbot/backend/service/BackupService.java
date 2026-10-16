package com.medicalchatbot.backend.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.config.properties.BackupProperties;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.BackupResponse;
import com.medicalchatbot.backend.dto.response.admin.RestoreResponse;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.BackupMapper;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database backups (app DB + HAPI FHIR DB) and restores. Jobs are recorded as RUNNING and the
 * scripts run in the background; the admin UI polls the history until they finish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BackupService {

    private static final String INTERRUPTED_BY_RESTART = "Tiến trình bị gián đoạn do backend khởi động lại.";

    BackupHistoryRepository backupHistoryRepository;
    RestoreHistoryRepository restoreHistoryRepository;
    BackupJobRunner backupJobRunner;
    BackupMapper backupMapper;
    BackupProperties properties;
    @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    TaskExecutor taskExecutor;

    @PreAuthorize("hasRole('ADMIN')")
    public BackupResponse startManualBackup(String username) {
        requireEnabled();
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw new AppException(ErrorCode.BACKUP_IN_PROGRESS);
        }
        return backupMapper.toBackupResponse(startBackup(BackupTrigger.MANUAL, username));
    }

    /**
     * A job still RUNNING at startup was cut off by a restart: its script died with the previous
     * process. Left as is, it would block every later backup and restore with a conflict. Assumes a
     * single backend instance, which is also what the in-process scheduler assumes.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failJobsInterruptedByRestart() {
        List<BackupHistory> backups = backupHistoryRepository.findAllByStatus(BackupStatus.RUNNING);
        List<RestoreHistory> restores = restoreHistoryRepository.findAllByStatus(BackupStatus.RUNNING);
        backups.forEach(backup -> backup.markFailed(INTERRUPTED_BY_RESTART));
        restores.forEach(restore -> restore.markFailed(INTERRUPTED_BY_RESTART));
        if (!backups.isEmpty() || !restores.isEmpty()) {
            log.warn("Marked {} backup(s) and {} restore(s) interrupted by a restart as FAILED",
                    backups.size(), restores.size());
        }
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

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<BackupResponse> backups(Pageable pageable) {
        return PageResponse.of(backupHistoryRepository.findAllByOrderByCreatedAtDesc(pageable), backupMapper::toBackupResponse);
    }

    /** Overwrites both databases with the chosen backup. Only one backup/restore job runs at a time. */
    @PreAuthorize("hasRole('ADMIN')")
    public RestoreResponse startRestore(UUID backupId, String username) {
        requireEnabled();
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)
                || restoreHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw new AppException(ErrorCode.BACKUP_IN_PROGRESS);
        }
        BackupHistory backup = backupHistoryRepository.findById(backupId)
                .orElseThrow(() -> new AppException(ErrorCode.BACKUP_NOT_EXISTED));
        if (!backup.isRestorable()) {
            throw new AppException(ErrorCode.BACKUP_NOT_RESTORABLE);
        }
        DumpFiles files = DumpFiles.of(backup.getItemsJson());
        if (files.isEmpty()) {
            throw new AppException(ErrorCode.BACKUP_NOT_RESTORABLE);
        }

        RestoreHistory restore = restoreHistoryRepository.save(RestoreHistory.builder()
                .backupId(backupId)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());
        RestoreJob job = new RestoreJob(restore.getId(), backupId, username, restore.getStartedAt(), files.app(), files.hapi());
        taskExecutor.execute(() -> backupJobRunner.runRestore(job));
        return backupMapper.toRestoreResponse(restore);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<RestoreResponse> restores(Pageable pageable) {
        return PageResponse.of(restoreHistoryRepository.findAllByOrderByCreatedAtDesc(pageable), backupMapper::toRestoreResponse);
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
            throw new AppException(ErrorCode.BACKUP_DISABLED);
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
