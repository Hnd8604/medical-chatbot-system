package com.medicalchatbot.backend.exception;

import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;

public class QuotaExceededException extends RuntimeException {

    private final QuotaStatusResponse quotaStatus;

    public QuotaExceededException(String message, QuotaStatusResponse quotaStatus) {
        super(message);
        this.quotaStatus = quotaStatus;
    }

    public QuotaStatusResponse quotaStatus() {
        return quotaStatus;
    }
}
