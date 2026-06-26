package com.medicalchatbot.backend.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 255)
    private String title;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "active_patient_id", length = 100)
    private String activePatientId;

    @Column(name = "memory_summary", columnDefinition = "text")
    private String memorySummary;

    @Column(name = "last_intent", length = 100)
    private String lastIntent;

    @Column(name = "last_tool_name", length = 100)
    private String lastToolName;

    @Column(name = "last_resource_type", length = 100)
    private String lastResourceType;

    @Column(name = "last_resource_id", length = 100)
    private String lastResourceId;

    public ChatSession(User user, String title) {
        this.user = user;
        this.title = title;
    }

    public ChatSession(UUID id) {
        this.id = id;
    }

    public ChatSessionMemory memory() {
        return new ChatSessionMemory(
                activePatientId,
                memorySummary,
                lastIntent,
                lastToolName,
                lastResourceType,
                lastResourceId
        );
    }

    public void applyMemory(ChatSessionMemory memory) {
        this.activePatientId = memory.activePatientId();
        this.memorySummary = memory.memorySummary();
        this.lastIntent = memory.lastIntent();
        this.lastToolName = memory.lastToolName();
        this.lastResourceType = memory.lastResourceType();
        this.lastResourceId = memory.lastResourceId();
        touch();
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
