package com.medicalchatbot.backend.exception;

import lombok.Getter;

/**
 * Expected application failure. Services throw it with an {@link ErrorCode}; the web layer turns
 * it into the matching HTTP status and error body, so services never depend on Spring MVC.
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** Replaces the default message, e.g. to name the offending value. */
    public AppException(ErrorCode errorCode, String customMessage) {
        super(customMessage != null ? customMessage : errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AppException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
