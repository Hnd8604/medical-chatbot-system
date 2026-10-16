package com.medicalchatbot.backend.entity;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
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
@Table(name = "backup_history")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BackupHistory extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    BackupTrigger triggerType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    BackupStatus status = BackupStatus.RUNNING;

    @Column(name = "triggered_by", length = 100)
    String triggeredBy;

    @Column(name = "started_at", nullable = false)
    OffsetDateTime startedAt;

    @Column(name = "finished_at")
    OffsetDateTime finishedAt;

    @Column(name = "total_size_bytes")
    Long totalSizeBytes;

    @Column(name = "upload_target", length = 255)
    String uploadTarget;

    /** Kết quả từng DB do {@code backup.sh} trả về: {@code [{db, file, size_bytes, ok, uploaded}]}. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", columnDefinition = "jsonb")
    JsonNode itemsJson = JsonNodeFactory.instance.arrayNode();

    @Column(name = "error_message", columnDefinition = "text")
    String errorMessage;

    public boolean isRestorable() {
        return status == BackupStatus.SUCCESS || status == BackupStatus.PARTIAL;
    }

    public void markFailed(String message) {
        this.status = BackupStatus.FAILED;
        this.finishedAt = OffsetDateTime.now();
        this.errorMessage = message;
    }
}
