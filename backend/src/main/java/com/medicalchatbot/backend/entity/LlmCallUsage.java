package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;

import com.medicalchatbot.backend.entity.converter.CostSourceConverter;
import com.medicalchatbot.backend.enums.CostSource;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/**
 * Một LLM call trong một lượt chat (router / planner / answer / summary...). Cho phép báo
 * cáo chi phí theo đúng model/stage — {@link UsageLog} chỉ giữ tổng của lượt.
 */
@Entity
@Table(name = "llm_call_usage")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LlmCallUsage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usage_log_id", nullable = false)
    UsageLog usageLog;

    @Column(nullable = false, length = 50)
    String stage;

    @Column(name = "llm_provider", length = 50)
    String llmProvider;

    @Column(name = "llm_model", length = 100)
    String llmModel;

    @Column(name = "input_tokens", nullable = false)
    int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    int outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    BigDecimal costUsd;

    /** {@link CostSource#GATEWAY} hoặc {@link CostSource#UNAVAILABLE}. */
    @Convert(converter = CostSourceConverter.class)
    @Column(name = "cost_source", nullable = false, length = 20)
    CostSource costSource;

    @Column(name = "gateway_call_id", length = 100)
    String gatewayCallId;
}
