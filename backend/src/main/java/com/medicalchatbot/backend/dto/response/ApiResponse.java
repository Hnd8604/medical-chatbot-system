package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * Body of every JSON response: {@code {"code": 1000, "message": ..., "result": ...}} on success,
 * {@code {"code": <ErrorCode>, "message": ...}} on failure.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        T result) {

    public static final int SUCCESS_CODE = 1000;

    public ApiResponse {
        // A builder without code leaves the int at 0: that means success.
        if (code == 0) {
            code = SUCCESS_CODE;
        }
    }
}
