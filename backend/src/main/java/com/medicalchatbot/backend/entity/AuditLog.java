package com.medicalchatbot.backend.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_logs")
@Getter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ChatSession session;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", length = 100)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode metadataJson = JsonNodeFactory.instance.objectNode();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditLog() {
    }

    public AuditLog(
            User user,
            ChatSession session,
            String action,
            String resourceType,
            String resourceId,
            JsonNode metadataJson
    ) {
        this.user = user;
        this.session = session;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.metadataJson = cleanMetadata(metadataJson);
    }

    private JsonNode cleanMetadata(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return value;
    }

    public UUID getUserId() {
        return user != null ? user.getId() : null;
    }

    public UUID getSessionId() {
        return session != null ? session.getId() : null;
    }
}
