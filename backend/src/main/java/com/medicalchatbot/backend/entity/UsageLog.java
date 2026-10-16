package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;

import com.medicalchatbot.backend.entity.converter.CostSourceConverter;
import com.medicalchatbot.backend.entity.converter.UsageStatusConverter;
import com.medicalchatbot.backend.enums.CostSource;
import com.medicalchatbot.backend.enums.UsageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/**
 * Một lượt gọi AI (lượt chat) — nguồn dữ liệu duy nhất cho quota và báo cáo chi phí.
 * Chi tiết từng LLM call của lượt nằm ở {@link LlmCallUsage}.
 */
@Entity
@Table(name = "usage_logs")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UsageLog extends BaseEntity {

    public static final String OPERATION_CHAT = "chat";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    ChatSession session;

    @Column(name = "llm_provider", length = 50)
    String llmProvider;

    @Column(name = "llm_model", length = 100)
    String llmModel;

    @Builder.Default
    @Column(nullable = false, length = 50)
    String operation = OPERATION_CHAT;

    @Builder.Default
    @Convert(converter = UsageStatusConverter.class)
    @Column(nullable = false, length = 30)
    UsageStatus status = UsageStatus.SUCCESS;

    @Column(name = "latency_ms")
    Integer latencyMs;

    @Builder.Default
    @Column(name = "request_count", nullable = false)
    int requestCount = 1;

    @Column(name = "input_tokens", nullable = false)
    int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    int outputTokens;

    /** Tổng chi phí AI của lượt, do gateway báo (xem {@link #costSource}). */
    @Builder.Default
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    BigDecimal costUsd = BigDecimal.ZERO;

    @Builder.Default
    @Convert(converter = CostSourceConverter.class)
    @Column(name = "cost_source", nullable = false, length = 20)
    CostSource costSource = CostSource.NONE;

    /** trace_id của lượt bên chatbot-service, để đối chiếu log. */
    @Column(name = "trace_id", length = 64)
    String traceId;

    @Column(name = "error_message", columnDefinition = "text")
    String errorMessage;

    @Column(name = "answer_source")
    String answerSource;

    @Builder.Default
    @Column(name = "saved_tokens", nullable = false)
    int savedTokens = 0;

    @Builder.Default
    @Column(name = "saved_cost_usd", nullable = false, precision = 12, scale = 6)
    BigDecimal savedCostUsd = BigDecimal.ZERO;
}
