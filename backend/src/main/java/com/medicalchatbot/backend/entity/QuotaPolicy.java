package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "quota_policies")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class QuotaPolicy extends BaseEntity {

    /** Policy gán cho tài khoản tự đăng ký. */
    public static final String DEFAULT_USER_POLICY = "user_standard";

    @Column(nullable = false, unique = true, length = 100)
    String name;

    @Column(name = "daily_request_limit", nullable = false)
    int dailyRequestLimit;

    @Column(name = "daily_token_limit", nullable = false)
    int dailyTokenLimit;

    @Column(name = "daily_cost_limit_usd", nullable = false, precision = 10, scale = 4)
    BigDecimal dailyCostLimitUsd;

    /** Số request chat tối đa mỗi phút; {@code null} dùng mặc định của hệ thống. */
    @Column(name = "rate_limit_per_minute")
    Integer rateLimitPerMinute;
}
