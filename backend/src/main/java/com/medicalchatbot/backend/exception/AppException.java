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
        this(errorCode, null, null);
    }

    public AppException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public AppException(ErrorCode errorCode, String message, Throwable cause) {
        super(message != null ? message : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public static AppException notFound(String message) {
        return new AppException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public static AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_ARGUMENT, message);
    }

    public static AppException conflict(String message) {
        return new AppException(ErrorCode.CONFLICT, message);
    }

    public static AppException forbidden(String message) {
        return new AppException(ErrorCode.ACCESS_DENIED, message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(ErrorCode.AUTHENTICATION_REQUIRED, message);
    }
}
