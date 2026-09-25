package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Stable envelope for successful REST responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T result) {

    public static final int SUCCESS_CODE = 1000;

    public ApiResponse {
        if (code == 0) {
            code = SUCCESS_CODE;
        }
    }

    public static <T> ApiResponse<T> success(T result) {
        return new ApiResponse<>(SUCCESS_CODE, null, result);
    }
}
