package com.medicalchatbot.backend.exception;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.enums.AlertSeverity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private final AlertService alertService;

    private Map<String, Object> buildErrorResponse(int status, String errorCode, String message) {
        return Map.of(
                "status", status,
                "error_code", errorCode,
                "message", message
        );
    }


    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("[HTTP STATUS ERROR] {}: {}", ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .body(buildErrorResponse(ex.getStatusCode().value(), ex.getStatusCode().toString(), ex.getReason()));
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllUncaughtException(Exception ex) {
        log.error("[SYSTEM FATAL] Lỗi hệ thống không xác định: ", ex);

        // M19: Báo động đỏ cho Admin ngay lập tức!
        alertService.triggerAlert(
                "SYSTEM_CORE",
                "FATAL_ERROR_500",
                AlertSeverity.CRITICAL,
                "Hệ thống sập! Chi tiết: " + ex.getMessage(),
                null
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse(500, "INTERNAL_SERVER_ERROR", "Hệ thống đang gặp sự cố nội bộ. Vui lòng thử lại sau."));
    }


    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(HttpClientErrorException.NotFound ex) {
        log.warn("[CLIENT ERROR] Không tìm thấy tài nguyên: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildErrorResponse(404, "NOT_FOUND", "Không tìm thấy tài nguyên trong chatbot-service."));
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> chatbotResponseError(RestClientResponseException ex) {
        log.error("[GATEWAY ERROR] Chatbot-service trả về mã lỗi {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        if (ex.getStatusCode().is5xxServerError()) {
            alertService.triggerAlert(
                    "AI_SERVICE",
                    "CHATBOT_SERVICE_500",
                    AlertSeverity.CRITICAL,
                    "Chatbot-service xử lý thất bại: " + ex.getResponseBodyAsString(),
                    null
            );
        }

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildErrorResponse(502, "CHATBOT_SERVICE_ERROR", "Chatbot-service xử lý thất bại."));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> chatbotUnavailable(RestClientException ex) {
        log.error("[GATEWAY ERROR] Không thể kết nối tới Chatbot-service: ", ex);

        alertService.triggerAlert(
                "AI_SERVICE",
                "CHATBOT_UNAVAILABLE",
                AlertSeverity.CRITICAL,
                "Không thể kết nối tới Chatbot-service.",
                null
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildErrorResponse(502, "CHATBOT_UNAVAILABLE", "Chatbot-service hiện không khả dụng."));
    }


    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> validationError(Exception ex) {
        log.warn("[VALIDATION ERROR] Dữ liệu đầu vào không hợp lệ: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(buildErrorResponse(400, "VALIDATION_ERROR", "Dữ liệu yêu cầu không hợp lệ."));
    }


    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quotaExceeded(QuotaExceededException ex) {
        log.info("[QUOTA] Chặn request do vượt hạn mức: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(buildErrorResponse(429, "QUOTA_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> rateLimitExceeded(RateLimitExceededException ex) {
        log.info("[RATE LIMIT] Chặn request do spam từ {}: {}", ex.getIdentifier(), ex.getMessage());

        alertService.triggerAlert(
                "SECURITY",
                "RATE_LIMIT_VIOLATION",
                AlertSeverity.WARNING,
                "Phát hiện hành vi spam API từ đối tượng: " + ex.getIdentifier(),
                null
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(buildErrorResponse(429, "RATE_LIMIT_EXCEEDED", ex.getMessage()));
    }
}