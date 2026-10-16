package com.medicalchatbot.backend.enums;

/**
 * Kết quả của một lượt gọi AI trong {@code usage_logs}. Lượt {@link #FAILED} vẫn đã tốn
 * token/tiền nên được cộng vào quota token/chi phí, nhưng không tính là một lượt request.
 */
public enum UsageStatus implements PersistedEnum {
    SUCCESS("success"),
    FAILED("failed");

    private final String value;

    UsageStatus(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
