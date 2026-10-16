package com.medicalchatbot.backend.exception;

import lombok.Getter;

/** A client sent more requests per minute than allowed (chat per user, public auth actions per IP). */
@Getter
public class RateLimitExceededException extends AppException {

    /** Who hit the limit, reported in the security alert. */
    private final String identifier;

    public RateLimitExceededException(String identifier) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
        this.identifier = identifier;
    }
}
