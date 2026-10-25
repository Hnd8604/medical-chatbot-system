package com.medicalchatbot.backend.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "chat_sessions")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChatSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    User user;

    @Column(length = 255)
    String title;

    @Embedded
    ChatMemory memory;

    /** memory_summary bao phủ tới tin nhắn thứ mấy của session (mốc rolling summary). */
    @Column(name = "memory_summary_message_count", nullable = false)
    int memorySummaryMessageCount;

    /** Hibernate maps an embeddable whose columns are all null to {@code null}. */
    public ChatMemory getMemory() {
        return memory == null ? ChatMemory.EMPTY : memory;
    }

    public void updateMemory(ChatMemory memory) {
        this.memory = memory;
        touch();
    }

    /** Summary mới vừa được lưu bao phủ mọi tin có trước lượt hiện tại. */
    public void markSummarizedThrough(int messageCount) {
        this.memorySummaryMessageCount = Math.max(messageCount, 0);
    }

    /** Moves the session to the top of the history list. */
    public void touch() {
        setUpdatedAt(OffsetDateTime.now());
    }
}
