package com.medicalchatbot.backend.exception;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import jakarta.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {


    private Map<String, Object> buildErrorResponse(int status, String errorCode, String message) {
        return Map.of(
                "status", status,
                "error_code", errorCode,
                "message", message
        );
    }

    // 1. CATCH-ALL (Lỗi hệ thống nghiêm trọng 500)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllUncaughtException(Exception ex) {
        log.error("[SYSTEM FATAL] Lỗi hệ thống không xác định: ", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse(500, "INTERNAL_SERVER_ERROR", "Hệ thống đang gặp sự cố nội bộ. Vui lòng thử lại sau."));
    }

    // 2. Lỗi giao tiếp với FastAPI
    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(HttpClientErrorException.NotFound ex) {
        log.warn("[CLIENT ERROR] Không tìm thấy tài nguyên: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildErrorResponse(404, "NOT_FOUND", "Không tìm thấy tài nguyên trong chatbot-service."));
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> chatbotResponseError(RestClientResponseException ex) {
        log.error("[GATEWAY ERROR] Chatbot-service trả về mã lỗi {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildErrorResponse(502, "CHATBOT_SERVICE_ERROR", "Chatbot-service xử lý thất bại."));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> chatbotUnavailable(RestClientException ex) {
        log.error("[GATEWAY ERROR] Không thể kết nối tới Chatbot-service: ", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildErrorResponse(502, "CHATBOT_UNAVAILABLE", "Chatbot-service hiện không khả dụng."));
    }

    // 3. Lỗi Validation
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> validationError(Exception ex) {
        log.warn("[VALIDATION ERROR] Dữ liệu đầu vào không hợp lệ: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(buildErrorResponse(400, "VALIDATION_ERROR", "Dữ liệu yêu cầu không hợp lệ."));
    }

    // 4. Lỗi Business Logic (Quota / Rate Limit)
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quotaExceeded(QuotaExceededException ex) {
        log.info("[QUOTA] Chặn request do vượt hạn mức: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(buildErrorResponse(429, "QUOTA_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> rateLimitExceeded(RateLimitExceededException ex) {
        log.info("[RATE LIMIT] Chặn request do spam: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(buildErrorResponse(429, "RATE_LIMIT_EXCEEDED", ex.getMessage()));
    }
}