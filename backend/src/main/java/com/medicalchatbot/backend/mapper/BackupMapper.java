package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.BackupResponse;
import com.medicalchatbot.backend.dto.response.RestoreResponse;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import org.springframework.stereotype.Component;

@Component
public class BackupMapper {

    public BackupResponse toResponse(BackupHistory backup) {
        return new BackupResponse(
                backup.getId(),
                backup.getTriggerType(),
                backup.getStatus(),
                backup.getTriggeredBy(),
                backup.getStartedAt(),
                backup.getFinishedAt(),
                backup.getTotalSizeBytes(),
                backup.getUploadTarget(),
                backup.getItemsJson(),
                backup.getErrorMessage(),
                backup.getCreatedAt()
        );
    }

    public RestoreResponse toResponse(RestoreHistory restore) {
        return new RestoreResponse(
                restore.getId(),
                restore.getBackupId(),
                restore.getStatus(),
                restore.getTriggeredBy(),
                restore.getStartedAt(),
                restore.getFinishedAt(),
                restore.getItemsJson(),
                restore.getErrorMessage(),
                restore.getCreatedAt()
        );
    }
}
