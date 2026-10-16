package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.config.properties.BackupProperties;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import com.medicalchatbot.backend.service.BackupService.RestoreJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackupJobRunnerTest {

    @Mock
    private BackupHistoryRepository backupHistoryRepository;

    @Mock
    private RestoreHistoryRepository restoreHistoryRepository;

    private BackupJobRunner runner;

    @BeforeEach
    void setUp() {
        BackupProperties properties = new BackupProperties(true, "backup.sh", "restore.sh", "bash", Duration.ofMinutes(10));
        runner = new BackupJobRunner(backupHistoryRepository, restoreHistoryRepository, new ObjectMapper(), properties);
    }

    @Test
    void backupResultUpdatesTheJob() {
        BackupHistory backup = backup();
        when(backupHistoryRepository.findById(backup.getId())).thenReturn(Optional.of(backup));

        runner.applyBackupResult(backup.getId(), """
                informational output
                BACKUP_RESULT_JSON={"status":"success","finished_at":"2026-09-25T01:02:00Z","upload_target":"drive","items":[{"db":"app","ok":true,"uploaded":true,"size_bytes":120},{"db":"hapi","ok":true,"uploaded":true,"size_bytes":30}]}
                """);

        assertEquals(BackupStatus.SUCCESS, backup.getStatus());
        assertEquals(150L, backup.getTotalSizeBytes());
        assertEquals("drive", backup.getUploadTarget());
        assertEquals(OffsetDateTime.parse("2026-09-25T01:02:00Z"), backup.getFinishedAt());
        assertNull(backup.getErrorMessage());
        verify(backupHistoryRepository).save(backup);
    }

    @Test
    void partialBackupExplainsWhichDatabaseFailed() {
        BackupHistory backup = backup();
        when(backupHistoryRepository.findById(backup.getId())).thenReturn(Optional.of(backup));

        runner.applyBackupResult(backup.getId(), """
                BACKUP_RESULT_JSON={"status":"partial","items":[{"db":"app","ok":true,"uploaded":false,"upload_error":"rclone timeout"},{"db":"hapi","ok":false}]}
                """);

        assertEquals(BackupStatus.PARTIAL, backup.getStatus());
        assertEquals("app: rclone timeout; hapi: dump lỗi;", backup.getErrorMessage());
    }

    @Test
    void missingResultMarkerFailsTheJob() {
        BackupHistory backup = backup();
        when(backupHistoryRepository.findById(backup.getId())).thenReturn(Optional.of(backup));

        runner.applyBackupResult(backup.getId(), "script failed before producing a result");

        assertEquals(BackupStatus.FAILED, backup.getStatus());
        assertEquals("Không đọc được kết quả sao lưu từ script. Output: script failed before producing a result",
                backup.getErrorMessage());
        verify(backupHistoryRepository).save(backup);
    }

    @Test
    void restoreResultRecreatesHistoryOverwrittenByTheRestore() {
        RestoreJob job = new RestoreJob(UUID.randomUUID(), UUID.randomUUID(), "admin",
                OffsetDateTime.parse("2026-09-25T02:00:00Z"), "app.sql.gz", "hapi.sql.gz");
        when(restoreHistoryRepository.findById(job.restoreId())).thenReturn(Optional.empty());

        runner.applyRestoreResult(job,
                "RESTORE_RESULT_JSON={\"status\":\"success\",\"finished_at\":\"2026-09-25T02:03:00Z\",\"items\":[]}");

        ArgumentCaptor<RestoreHistory> restore = ArgumentCaptor.forClass(RestoreHistory.class);
        verify(restoreHistoryRepository).save(restore.capture());
        assertEquals(job.restoreId(), restore.getValue().getId());
        assertEquals(job.backupId(), restore.getValue().getBackupId());
        assertEquals("admin", restore.getValue().getTriggeredBy());
        assertEquals(BackupStatus.SUCCESS, restore.getValue().getStatus());
    }

    @Test
    void failedRestoreExplainsEachDatabaseError() {
        RestoreJob job = new RestoreJob(UUID.randomUUID(), UUID.randomUUID(), "admin", OffsetDateTime.now(), "a", "h");
        when(restoreHistoryRepository.findById(job.restoreId())).thenReturn(Optional.empty());
        when(restoreHistoryRepository.save(any(RestoreHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        runner.applyRestoreResult(job,
                "RESTORE_RESULT_JSON={\"status\":\"failed\",\"items\":[{\"db\":\"hapi\",\"ok\":false,\"error\":\"psql exited 1\"}]}");

        ArgumentCaptor<RestoreHistory> restore = ArgumentCaptor.forClass(RestoreHistory.class);
        verify(restoreHistoryRepository).save(restore.capture());
        assertEquals("hapi: psql exited 1;", restore.getValue().getErrorMessage());
    }

    private static BackupHistory backup() {
        return BackupHistory.builder()
                .id(UUID.randomUUID())
                .triggerType(BackupTrigger.MANUAL)
                .startedAt(OffsetDateTime.parse("2026-09-25T01:00:00Z"))
                .build();
    }
}
