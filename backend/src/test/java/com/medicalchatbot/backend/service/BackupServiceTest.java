package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.BackupProperties;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.mapper.BackupMapper;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    @Mock
    private BackupHistoryRepository backupHistoryRepository;

    @Mock
    private RestoreHistoryRepository restoreHistoryRepository;

    @Mock
    private BackupJobRunner backupJobRunner;

    @Mock
    private BackupMapper backupMapper;

    @Mock
    private TaskExecutor taskExecutor;

    private BackupService service;

    @BeforeEach
    void setUp() {
        BackupProperties properties = new BackupProperties(true, "backup.sh", "restore.sh", "bash", Duration.ofMinutes(10));
        service = new BackupService(backupHistoryRepository, restoreHistoryRepository, backupJobRunner, backupMapper,
                properties, taskExecutor);
    }

    @Test
    void jobsLeftRunningByARestartAreFailedSoTheyNoLongerBlockNewJobs() {
        BackupHistory backup = BackupHistory.builder()
                .id(UUID.randomUUID())
                .triggerType(BackupTrigger.AUTO)
                .startedAt(OffsetDateTime.now().minusHours(1))
                .build();
        RestoreHistory restore = RestoreHistory.builder()
                .id(UUID.randomUUID())
                .backupId(backup.getId())
                .startedAt(OffsetDateTime.now().minusMinutes(5))
                .build();
        when(backupHistoryRepository.findAllByStatus(BackupStatus.RUNNING)).thenReturn(List.of(backup));
        when(restoreHistoryRepository.findAllByStatus(BackupStatus.RUNNING)).thenReturn(List.of(restore));

        service.failJobsInterruptedByRestart();

        assertEquals(BackupStatus.FAILED, backup.getStatus());
        assertEquals("Tiến trình bị gián đoạn do backend khởi động lại.", backup.getErrorMessage());
        assertNotNull(backup.getFinishedAt());
        assertEquals(BackupStatus.FAILED, restore.getStatus());
        assertNotNull(restore.getFinishedAt());
    }
}
