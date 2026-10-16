package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.medicalchatbot.backend.entity.converter.CostSourceConverter;
import com.medicalchatbot.backend.entity.converter.UsageStatusConverter;
import com.medicalchatbot.backend.enums.CostSource;
import com.medicalchatbot.backend.enums.UsageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Một lượt gọi AI (lượt chat) — nguồn dữ liệu duy nhất cho quota và báo cáo chi phí.
 * Chi tiết từng LLM call của lượt nằm ở {@link LlmCallUsage}.
 */
@Entity
@Table(name = "usage_logs")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UsageLog {

    public static final String OPERATION_CHAT = "chat";

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

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String operation = OPERATION_CHAT;

    @Builder.Default
    @Convert(converter = UsageStatusConverter.class)
    @Column(nullable = false, length = 30)
    private UsageStatus status = UsageStatus.SUCCESS;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Builder.Default
    @Column(name = "request_count", nullable = false)
    private int requestCount = 1;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    /**
     * Tổng chi phí AI của lượt. Giữ tên cột cũ; từ V10 là cost thật của gateway (dòng
     * {@link #costSource} = legacy_estimate là ước tính cũ trước V10).
     */
    @Builder.Default
    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Builder.Default
    @Convert(converter = CostSourceConverter.class)
    @Column(name = "cost_source", nullable = false, length = 20)
    private CostSource costSource = CostSource.NONE;

    /** trace_id của lượt bên chatbot-service, để đối chiếu log. */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "answer_source")
    private String answerSource;

    @Builder.Default
    @Column(name = "saved_tokens")
    private Integer savedTokens = 0;

    @Builder.Default
    @Column(name = "saved_cost_usd")
    private BigDecimal savedCostUsd = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
