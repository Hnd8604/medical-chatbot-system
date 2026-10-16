package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.admin.BackupResponse;
import com.medicalchatbot.backend.dto.response.admin.RestoreResponse;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface BackupMapper {

    @Mapping(target = "items", source = "itemsJson")
    BackupResponse toBackupResponse(BackupHistory backup);

    @Mapping(target = "items", source = "itemsJson")
    RestoreResponse toRestoreResponse(RestoreHistory restore);
}
