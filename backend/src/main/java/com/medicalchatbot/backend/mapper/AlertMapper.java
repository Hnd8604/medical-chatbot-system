package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.admin.AlertResponse;
import com.medicalchatbot.backend.entity.Alert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface AlertMapper {

    @Mapping(target = "metadata", source = "metadataJson")
    AlertResponse toAlertResponse(Alert alert);
}
