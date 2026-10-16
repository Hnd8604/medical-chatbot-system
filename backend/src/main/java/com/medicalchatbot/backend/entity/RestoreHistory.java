package com.medicalchatbot.backend.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.enums.BackupStatus;
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
@Table(name = "restore_history")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RestoreHistory extends BaseEntity {

    @Column(name = "backup_id")
    UUID backupId;

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

    /** Kết quả từng DB do {@code restore-auto.sh} trả về: {@code [{db, file, ok, error}]}. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", columnDefinition = "jsonb")
    JsonNode itemsJson = JsonNodeFactory.instance.arrayNode();

    @Column(name = "error_message", columnDefinition = "text")
    String errorMessage;

    public void markFailed(String message) {
        this.status = BackupStatus.FAILED;
        this.finishedAt = OffsetDateTime.now();
        this.errorMessage = message;
    }
}
