package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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

@Entity
@Table(name = "usage_logs")
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ChatSession session;

    @Column(name = "llm_provider", length = 50)
    private String llmProvider;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(nullable = false, length = 50)
    private String operation = "chat";

    @Column(nullable = false, length = 30)
    private String status = "success";

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "request_count", nullable = false)
    private int requestCount = 1;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "answer_source")
    private String answerSource;

    @Column(name = "saved_tokens")
    private Integer savedTokens;

    @Column(name = "saved_cost_usd")
    private BigDecimal savedCostUsd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UsageLog() {
    }

    public UsageLog(
            User user,
            ChatSession session,
            String llmProvider,
            String llmModel,
            String operation,
            String status,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            BigDecimal estimatedCostUsd,
            String errorMessage,
            String answerSource,
            Integer savedTokens,
            BigDecimal savedCostUsd
    ) {
        this.user = user;
        this.session = session;
        this.llmProvider = llmProvider;
        this.llmModel = llmModel;
        this.operation = operation;
        this.status = status;
        this.latencyMs = Math.toIntExact(Math.max(0, latencyMs));
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimatedCostUsd = estimatedCostUsd == null ? BigDecimal.ZERO : estimatedCostUsd;
        this.errorMessage = errorMessage;

        this.answerSource = answerSource != null ? answerSource : "llm";
        this.savedTokens = savedTokens != null ? savedTokens : 0;
        this.savedCostUsd = savedCostUsd != null ? savedCostUsd : BigDecimal.ZERO;
    }
}
