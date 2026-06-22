package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "quota_policies")
public class QuotaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "daily_request_limit", nullable = false)
    private int dailyRequestLimit;

    @Column(name = "daily_token_limit", nullable = false)
    private int dailyTokenLimit;

    @Column(name = "daily_cost_limit_usd", nullable = false, precision = 10, scale = 4)
    private BigDecimal dailyCostLimitUsd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    protected QuotaPolicy() {
    }

    public QuotaPolicy(
            String name,
            int dailyRequestLimit,
            int dailyTokenLimit,
            BigDecimal dailyCostLimitUsd,
            Integer rateLimitPerMinute
    ) {
        this.name = name;
        this.dailyRequestLimit = dailyRequestLimit;
        this.dailyTokenLimit = dailyTokenLimit;
        this.dailyCostLimitUsd = dailyCostLimitUsd;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

   
    public void applyUpdate(
            String name,
            int dailyRequestLimit,
            int dailyTokenLimit,
            BigDecimal dailyCostLimitUsd,
            Integer rateLimitPerMinute
    ) {
        this.name = name;
        this.dailyRequestLimit = dailyRequestLimit;
        this.dailyTokenLimit = dailyTokenLimit;
        this.dailyCostLimitUsd = dailyCostLimitUsd;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getDailyRequestLimit() {
        return dailyRequestLimit;
    }

    public int getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public BigDecimal getDailyCostLimitUsd() {
        return dailyCostLimitUsd;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public Integer getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }
}
