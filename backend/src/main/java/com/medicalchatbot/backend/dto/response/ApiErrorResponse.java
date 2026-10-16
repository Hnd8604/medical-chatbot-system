package com.medicalchatbot.backend.dto.response;

import com.medicalchatbot.backend.exception.ErrorCode;

/**
 * Body of every failed REST response. {@code code} and {@code errorCode} are stable identifiers
 * clients can branch on; {@code message} is safe to show to the user.
 */
public record ApiErrorResponse(int code, String errorCode, int status, String message) {

    public static ApiErrorResponse of(ErrorCode errorCode, int status, String publicMessage) {
        String message = publicMessage == null || publicMessage.isBlank()
                ? errorCode.getDefaultMessage()
                : publicMessage;
        return new ApiErrorResponse(errorCode.getCode(), errorCode.getKey(), status, message);
    }
}
