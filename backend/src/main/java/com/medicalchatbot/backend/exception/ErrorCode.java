package com.medicalchatbot.backend.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable application errors exposed by the REST API.
 *
 * <p>{@link #code} and {@link #key} are client contracts and must never be reused for a different
 * meaning; {@link #status} only describes HTTP semantics.</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Identity and access (11xx)
    AUTHENTICATION_REQUIRED(1101, "UNAUTHORIZED", HttpStatus.UNAUTHORIZED,
            "Bạn cần đăng nhập để tiếp tục."),
    ACCESS_DENIED(1102, "FORBIDDEN", HttpStatus.FORBIDDEN,
            "Bạn không có quyền truy cập tài nguyên này."),

    // Quota and traffic control (40xx)
    QUOTA_EXCEEDED(4001, "QUOTA_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS,
            "Bạn đã sử dụng hết hạn mức hiện tại."),
    RATE_LIMIT_EXCEEDED(4002, "RATE_LIMIT_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS,
            "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau."),

    // External services (50xx)
    CHATBOT_REQUEST_REJECTED(5001, "CHATBOT_SERVICE_REQUEST_REJECTED", HttpStatus.BAD_REQUEST,
            "Yêu cầu đã bị dịch vụ AI từ chối."),
    CHATBOT_SERVICE_ERROR(5002, "CHATBOT_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
            "Dịch vụ AI xử lý thất bại."),
    CHATBOT_UNAVAILABLE(5003, "CHATBOT_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
            "Dịch vụ AI hiện không khả dụng."),
    UPSTREAM_SERVICE_ERROR(5004, "UPSTREAM_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
            "Dịch vụ phụ thuộc xử lý thất bại."),

    // HTTP, request processing and platform errors (90xx)
    MALFORMED_REQUEST(9001, "MALFORMED_REQUEST", HttpStatus.BAD_REQUEST,
            "Nội dung yêu cầu không đúng định dạng."),
    METHOD_NOT_ALLOWED(9002, "METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED,
            "Phương thức HTTP không được hỗ trợ."),
    ENDPOINT_NOT_FOUND(9003, "ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND,
            "Không tìm thấy API được yêu cầu."),
    PAYLOAD_TOO_LARGE(9004, "PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE,
            "Dữ liệu tải lên vượt quá giới hạn cho phép."),
    DATA_CONFLICT(9005, "DATA_CONFLICT", HttpStatus.CONFLICT,
            "Dữ liệu xung đột với bản ghi hiện có."),
    UNSUPPORTED_MEDIA_TYPE(9006, "UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Định dạng nội dung không được hỗ trợ."),
    NOT_ACCEPTABLE(9007, "NOT_ACCEPTABLE", HttpStatus.NOT_ACCEPTABLE,
            "Định dạng phản hồi được yêu cầu không được hỗ trợ."),
    ASYNC_REQUEST_TIMEOUT(9008, "REQUEST_TIMEOUT", HttpStatus.SERVICE_UNAVAILABLE,
            "Yêu cầu đã hết thời gian xử lý."),
    VALIDATION_ERROR(9009, "VALIDATION_ERROR", HttpStatus.BAD_REQUEST,
            "Dữ liệu yêu cầu không hợp lệ."),
    INVALID_ARGUMENT(9010, "INVALID_ARGUMENT", HttpStatus.BAD_REQUEST,
            "Tham số yêu cầu không hợp lệ."),
    RESOURCE_NOT_FOUND(9011, "NOT_FOUND", HttpStatus.NOT_FOUND,
            "Không tìm thấy tài nguyên."),
    CONFLICT(9012, "CONFLICT", HttpStatus.CONFLICT,
            "Yêu cầu xung đột với trạng thái hiện tại của tài nguyên."),
    UNPROCESSABLE_ENTITY(9013, "UNPROCESSABLE_ENTITY", HttpStatus.UNPROCESSABLE_ENTITY,
            "Không thể xử lý dữ liệu yêu cầu."),
    TOO_MANY_REQUESTS(9014, "TOO_MANY_REQUESTS", HttpStatus.TOO_MANY_REQUESTS,
            "Có quá nhiều yêu cầu. Vui lòng thử lại sau."),
    SERVICE_UNAVAILABLE(9015, "SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
            "Dịch vụ hiện không khả dụng."),

    INTERNAL_SERVER_ERROR(9999, "INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR,
            "Hệ thống phát sinh lỗi nội bộ.");

    /** Numeric identifier, unique per error. */
    private final int code;
    /** Symbolic identifier sent as {@code error_code}. */
    private final String key;
    private final HttpStatus status;
    private final String defaultMessage;

    /** Closest application error for a bare HTTP status (framework exceptions, upstream errors). */
    public static ErrorCode fromStatus(HttpStatusCode status) {
        if (status == null) {
            return INTERNAL_SERVER_ERROR;
        }
        return switch (status.value()) {
            case 400 -> INVALID_ARGUMENT;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> RESOURCE_NOT_FOUND;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> UNPROCESSABLE_ENTITY;
            case 429 -> TOO_MANY_REQUESTS;
            case 502 -> UPSTREAM_SERVICE_ERROR;
            case 503, 504 -> SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? INVALID_ARGUMENT : INTERNAL_SERVER_ERROR;
        };
    }
}
