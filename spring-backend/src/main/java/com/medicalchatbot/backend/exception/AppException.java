package com.medicalchatbot.backend.exception;

import org.springframework.web.server.ResponseStatusException;

/** A typed application exception that remains compatible with ResponseStatusException. */
public class AppException extends ResponseStatusException {

    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public AppException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public AppException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, errorCode.getDefaultMessage(), cause);
    }

    public AppException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode.getStatus(), message != null ? message : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
