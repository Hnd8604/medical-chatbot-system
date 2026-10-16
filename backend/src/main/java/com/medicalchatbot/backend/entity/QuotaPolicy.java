package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "quota_policies")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class QuotaPolicy {

    /** Policy gán cho tài khoản tự đăng ký. */
    public static final String DEFAULT_USER_POLICY = "user_standard";

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

    /** Số request chat tối đa mỗi phút; {@code null} dùng mặc định của hệ thống. */
    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
