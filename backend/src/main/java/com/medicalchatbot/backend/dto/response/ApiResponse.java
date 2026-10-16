package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Envelope of every successful REST response: {@code {"code": 1000, "result": ...}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T result) {

    public static final int SUCCESS_CODE = 1000;

    public static <T> ApiResponse<T> success(T result) {
        return new ApiResponse<>(SUCCESS_CODE, null, result);
    }
}
