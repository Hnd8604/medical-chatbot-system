package com.medicalchatbot.backend.enums;

import java.util.Collection;

/**
 * Nguồn của số tiền AI đã ghi. Gateway (LiteLLM) là nguồn chi phí duy nhất — Spring
 * không giữ bảng giá và không tự ước tính.
 */
public enum CostSource {
    /** Cost thật do gateway báo (header x-litellm-response-cost). */
    GATEWAY("gateway"),
    /** Có call gateway không báo cost: call đó ghi 0, tổng bị thiếu. */
    UNAVAILABLE("unavailable"),
    /** Không có LLM call nào (cache hit, câu template...). */
    NONE("none"),
    /** Dòng usage_logs trước V10: cost do Spring ước tính theo bảng giá cũ. */
    LEGACY_ESTIMATE("legacy_estimate");

    private final String value;

    CostSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Nguồn của tổng một lượt chat, gộp từ nguồn của từng call. */
    public static CostSource aggregate(Collection<CostSource> callSources) {
        if (callSources.isEmpty()) {
            return NONE;
        }
        return callSources.contains(UNAVAILABLE) ? UNAVAILABLE : GATEWAY;
    }
}
