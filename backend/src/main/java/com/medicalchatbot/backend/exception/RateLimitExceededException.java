package com.medicalchatbot.backend.exception;

import lombok.Getter;

/** The user sent more chat requests per minute than their quota policy allows. */
@Getter
public class RateLimitExceededException extends AppException {

    /** Who hit the limit (the rate-limit key), reported in the security alert. */
    private final String identifier;

    public RateLimitExceededException(String identifier) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, "Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút.");
        this.identifier = identifier;
    }
}
