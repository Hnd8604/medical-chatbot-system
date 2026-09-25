package com.medicalchatbot.backend.exception;

/**
 * Transport-agnostic application failure.
 *
 * <p>The web layer translates its {@link ErrorCode}; services no longer need to
 * throw a Spring MVC exception just to communicate an expected failure.</p>
 */
public class AppException extends RuntimeException {

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
        super(message != null ? message : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
