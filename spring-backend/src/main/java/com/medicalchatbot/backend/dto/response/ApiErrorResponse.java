package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.medicalchatbot.backend.exception.ErrorCode;

/**
 * Backward-compatible error body. The original fields remain unchanged and
 * {@code code} adds a stable numeric application error identifier.
 */
@JsonPropertyOrder({"status", "error_code", "message", "detail", "code"})
public record ApiErrorResponse(
        int status,
        @JsonProperty("error_code") String errorCode,
        String message,
        String detail,
        int code
) {
    public static ApiErrorResponse from(ErrorCode errorCode) {
        return from(errorCode, errorCode.getStatus().value(), null);
    }

    public static ApiErrorResponse from(ErrorCode errorCode, String publicMessage) {
        return from(errorCode, errorCode.getStatus().value(), publicMessage);
    }

    public static ApiErrorResponse from(ErrorCode errorCode, int status, String publicMessage) {
        String safeMessage = publicMessage == null || publicMessage.isBlank()
                ? errorCode.getDefaultMessage()
                : publicMessage;
        return new ApiErrorResponse(
                status,
                errorCode.getErrorCode(),
                safeMessage,
                safeMessage,
                errorCode.getCode()
        );
    }
}
