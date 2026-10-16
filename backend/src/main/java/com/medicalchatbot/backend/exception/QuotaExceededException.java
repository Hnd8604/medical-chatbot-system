package com.medicalchatbot.backend.exception;

import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import lombok.Getter;

/** The user has used up a daily limit of their quota policy. */
@Getter
public class QuotaExceededException extends AppException {

    private final QuotaStatusResponse quotaStatus;

    public QuotaExceededException(QuotaStatusResponse quotaStatus) {
        super(ErrorCode.QUOTA_EXCEEDED, quotaStatus.blockedReason());
        this.quotaStatus = quotaStatus;
    }
}
