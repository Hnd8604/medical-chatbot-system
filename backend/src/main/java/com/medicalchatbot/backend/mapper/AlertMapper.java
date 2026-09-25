package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.AlertResponse;
import com.medicalchatbot.backend.entity.Alert;
import org.springframework.stereotype.Component;

@Component
public class AlertMapper {

    public AlertResponse toResponse(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getSource(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getMessage(),
                alert.getMetadataJson(),
                alert.getCreatedAt(),
                alert.getResolvedAt(),
                alert.getResolvedBy()
        );
    }
}
