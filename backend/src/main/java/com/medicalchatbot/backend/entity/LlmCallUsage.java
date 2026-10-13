package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Một LLM call trong một lượt chat (router / planner / answer / summary...). Cho phép báo
 * cáo chi phí theo đúng model/stage — {@link UsageLog} chỉ giữ tổng của lượt.
 */
@Entity
@Table(name = "llm_call_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmCallUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usage_log_id", nullable = false)
    private UsageLog usageLog;

    @Column(nullable = false, length = 50)
    private String stage;

    @Column(name = "llm_provider", length = 50)
    private String llmProvider;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd;

    /** gateway | unavailable — xem {@link com.medicalchatbot.backend.enums.CostSource}. */
    @Column(name = "cost_source", nullable = false, length = 20)
    private String costSource;

    @Column(name = "gateway_call_id", length = 100)
    private String gatewayCallId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
