package com.medicalchatbot.backend.dto.request.admin;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Create or replace a quota policy; {@code rateLimitPerMinute} is optional. */
public record QuotaPolicyRequest(
        @NotBlank(message = "Vui lòng nhập tên chính sách.")
        @Size(max = 100, message = "Tên chính sách không được vượt quá {max} ký tự.")
        String name,

        @NotNull(message = "Vui lòng nhập số lượt gọi AI tối đa mỗi ngày.")
        @Positive(message = "Số lượt gọi AI tối đa mỗi ngày phải lớn hơn 0.")
        Integer dailyRequestLimit,

        @NotNull(message = "Vui lòng nhập số token tối đa mỗi ngày.")
        @Positive(message = "Số token tối đa mỗi ngày phải lớn hơn 0.")
        Integer dailyTokenLimit,

        @NotNull(message = "Vui lòng nhập chi phí tối đa mỗi ngày.")
        @DecimalMin(value = "0.0", inclusive = false, message = "Chi phí tối đa mỗi ngày phải lớn hơn 0.")
        BigDecimal dailyCostLimitUsd,

        @Positive(message = "Số request mỗi phút phải lớn hơn 0.")
        Integer rateLimitPerMinute
) {
}
