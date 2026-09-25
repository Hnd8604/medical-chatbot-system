package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Virtual key cua LiteLLM Gateway gan 1-1 voi mot app_user. Gateway la nguon
 * chan budget token/cost; hang nay chi luu tro anh xa user -> key + budget hien tai.
 *
 * <p>Chu y: {@code virtualKey} luu dang plaintext (can de goi /key/info va truyen
 * xuong chatbot-service). Chap nhan cho pham vi demo; production nen ma hoa.
 */
@Entity
@Table(name = "llm_virtual_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmVirtualKey {

    /** Trung voi app_users.id (quan he 1-1). */
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "key_alias", nullable = false, length = 150)
    private String keyAlias;

    @Column(name = "virtual_key", nullable = false, length = 255)
    private String virtualKey;

    @Column(name = "max_budget_usd", nullable = false, precision = 10, scale = 4)
    private BigDecimal maxBudgetUsd;

    @Column(name = "budget_duration", nullable = false, length = 20)
    private String budgetDuration;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
