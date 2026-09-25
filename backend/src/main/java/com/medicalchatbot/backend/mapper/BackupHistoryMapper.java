package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.BackupHistoryResponse;
import com.medicalchatbot.backend.dto.response.RestoreHistoryResponse;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import org.springframework.stereotype.Component;

@Component
public class BackupHistoryMapper {

    public BackupHistoryResponse toResponse(BackupHistory history) {
        return new BackupHistoryResponse(
                history.getId(),
                history.getTriggerType(),
                history.getStatus(),
                history.getTriggeredBy(),
                history.getStartedAt(),
                history.getFinishedAt(),
                history.getTotalSizeBytes(),
                history.getUploadTarget(),
                history.getItemsJson(),
                history.getErrorMessage(),
                history.getCreatedAt()
        );
    }

    public RestoreHistoryResponse toResponse(RestoreHistory history) {
        return new RestoreHistoryResponse(
                history.getId(),
                history.getBackupId(),
                history.getStatus(),
                history.getTriggeredBy(),
                history.getStartedAt(),
                history.getFinishedAt(),
                history.getItemsJson(),
                history.getErrorMessage(),
                history.getCreatedAt()
        );
    }
}
