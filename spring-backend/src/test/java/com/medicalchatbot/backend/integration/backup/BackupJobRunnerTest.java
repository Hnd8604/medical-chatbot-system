package com.medicalchatbot.backend.integration.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        runner = new BackupJobRunner(
                backupHistoryRepository,
                restoreHistoryRepository,
                new ObjectMapper()
        );
    }

    @Test
    void parseBackupResultUpdatesSuccessfulJob() {
        UUID id = UUID.randomUUID();
        BackupHistory history = BackupHistory.builder()
                .id(id)
                .triggerType(BackupTrigger.MANUAL)
                .startedAt(OffsetDateTime.parse("2026-09-25T01:00:00Z"))
                .build();
        when(backupHistoryRepository.findById(id)).thenReturn(Optional.of(history));

        runner.parseBackupResult(id, """
                informational output
                BACKUP_RESULT_JSON={"status":"success","finished_at":"2026-09-25T01:02:00Z","upload_target":"drive","items":[{"db":"app","ok":true,"uploaded":true,"size_bytes":120},{"db":"hapi","ok":true,"uploaded":true,"size_bytes":30}]}
                """);

        assertEquals(BackupStatus.SUCCESS, history.getStatus());
        assertEquals(150L, history.getTotalSizeBytes());
        assertEquals("drive", history.getUploadTarget());
        assertEquals(OffsetDateTime.parse("2026-09-25T01:02:00Z"), history.getFinishedAt());
        assertNull(history.getErrorMessage());
        verify(backupHistoryRepository).save(history);
    }

    @Test
    void parseBackupResultMarksJobFailedWhenMarkerIsMissing() {
        UUID id = UUID.randomUUID();
        BackupHistory history = BackupHistory.builder()
                .id(id)
                .triggerType(BackupTrigger.AUTO)
                .startedAt(OffsetDateTime.now())
                .build();
        when(backupHistoryRepository.findById(id)).thenReturn(Optional.of(history));

        runner.parseBackupResult(id, "script failed before producing a result");

        assertEquals(BackupStatus.FAILED, history.getStatus());
        assertEquals("Không đọc được kết quả sao lưu từ script. Output: script failed before producing a result",
                history.getErrorMessage());
        verify(backupHistoryRepository).save(history);
    }

    @Test
    void parseRestoreResultRebuildsHistoryRemovedByDatabaseRestore() {
        UUID id = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-09-25T02:00:00Z");
        when(restoreHistoryRepository.findById(id)).thenReturn(Optional.empty());
        when(restoreHistoryRepository.save(any(RestoreHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        runner.parseRestoreResult(
                id,
                backupId,
                "admin",
                startedAt,
                "RESTORE_RESULT_JSON={\"status\":\"success\",\"finished_at\":\"2026-09-25T02:03:00Z\",\"items\":[]}"
        );

        verify(restoreHistoryRepository).save(any(RestoreHistory.class));
    }
}
