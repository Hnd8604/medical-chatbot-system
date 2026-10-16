package com.medicalchatbot.backend.entity;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "alerts")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Alert extends BaseEntity {

    /** Hệ thống phát sinh cảnh báo, vd {@code AI_SERVICE}, {@code QUOTA_SYSTEM}. */
    @Column(nullable = false, length = 50)
    String source;

    /** Tên một {@link com.medicalchatbot.backend.enums.AlertType}. */
    @Column(name = "alert_type", nullable = false, length = 100)
    String alertType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    AlertSeverity severity;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    AlertStatus status = AlertStatus.OPEN;

    @Column(nullable = false, columnDefinition = "text")
    String message;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    JsonNode metadataJson = JsonNodeFactory.instance.objectNode();

    @Column(name = "resolved_at")
    OffsetDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    String resolvedBy;

    /** Marks an open alert as handled; resolving twice keeps the first resolution. */
    public void resolve(String resolvedBy) {
        if (status != AlertStatus.OPEN) {
            return;
        }
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = OffsetDateTime.now();
        this.resolvedBy = resolvedBy;
    }
}
