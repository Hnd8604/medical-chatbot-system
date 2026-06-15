package com.medicalchatbot.backend.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private final ObjectProvider<AlertService> alertServiceProvider;
    private final ObjectProvider<NotificationService> notificationServiceProvider;
    private final ObjectProvider<CurrentUserService> currentUserServiceProvider;
    private final ObjectProvider<UserRepository> userRepositoryProvider;

    public ApiExceptionHandler(
            ObjectProvider<AlertService> alertServiceProvider,
            ObjectProvider<NotificationService> notificationServiceProvider,
            ObjectProvider<CurrentUserService> currentUserServiceProvider,
            ObjectProvider<UserRepository> userRepositoryProvider
    ) {
        this.alertServiceProvider = alertServiceProvider;
        this.notificationServiceProvider = notificationServiceProvider;
        this.currentUserServiceProvider = currentUserServiceProvider;
        this.userRepositoryProvider = userRepositoryProvider;
    }

    private Map<String, Object> buildErrorResponse(int status, String errorCode, String detail) {
        String safeDetail = detail != null ? detail : "Unexpected error.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error_code", errorCode);
        body.put("message", safeDetail);
        body.put("detail", safeDetail);
        return body;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("[HTTP STATUS ERROR] {}: {}", ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .body(buildErrorResponse(
                        ex.getStatusCode().value(),
                        ex.getStatusCode().toString(),
                        ex.getReason()
                ));
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(HttpClientErrorException.NotFound ex) {
        log.warn("[CLIENT ERROR] Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildErrorResponse(
                        404,
                        "NOT_FOUND",
                        "Kh\u00f4ng t\u00ecm th\u1ea5y t\u00e0i nguy\u00ean trong chatbot-service."
                ));
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> chatbotResponseError(RestClientResponseException ex) {
        log.error("[GATEWAY ERROR] Chatbot-service returned status {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        if (ex.getStatusCode().is5xxServerError()) {
            triggerAlert(
                    "AI_SERVICE",
                    "CHATBOT_SERVICE_500",
                    AlertSeverity.CRITICAL,
                    "Chatbot-service x\u1eed l\u00fd th\u1ea5t b\u1ea1i: " + ex.getResponseBodyAsString(),
                    null
            );
        }
        notifyCurrentUser(
                NotificationType.SYSTEM_ERROR,
                "D\u1ecbch v\u1ee5 AI tr\u1ea3 v\u1ec1 l\u1ed7i (AI Service Error)",
                "D\u1ecbch v\u1ee5 chatbot-service tr\u1ea3 v\u1ec1 m\u00e3 tr\u1ea1ng th\u00e1i: " + ex.getStatusCode()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildErrorResponse(502, "CHATBOT_SERVICE_ERROR", "Chatbot-service x\u1eed l\u00fd th\u1ea5t b\u1ea1i."));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> chatbotUnavailable(RestClientException ex) {
        log.error("[GATEWAY ERROR] Chatbot-service unavailable: ", ex);
        triggerAlert(
                "AI_SERVICE",
                "CHATBOT_UNAVAILABLE",
                AlertSeverity.CRITICAL,
                "Kh\u00f4ng th\u1ec3 k\u1ebft n\u1ed1i t\u1edbi Chatbot-service.",
                null
        );
        notifyCurrentUser(
                NotificationType.SYSTEM_ERROR,
                "L\u1ed7i k\u1ebft n\u1ed1i d\u1ecbch v\u1ee5 AI (AI Service Down)",
                "Kh\u00f4ng th\u1ec3 k\u1ebft n\u1ed1i \u0111\u1ebfn chatbot-service: " + ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildErrorResponse(502, "CHATBOT_UNAVAILABLE", "Chatbot-service hi\u1ec7n kh\u00f4ng kh\u1ea3 d\u1ee5ng."));
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> validationError(Exception ex) {
        log.warn("[VALIDATION ERROR] Invalid request data: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(buildErrorResponse(400, "VALIDATION_ERROR", "D\u1eef li\u1ec7u y\u00eau c\u1ea7u kh\u00f4ng h\u1ee3p l\u1ec7."));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quotaExceeded(QuotaExceededException ex) {
        log.info("[QUOTA] Request blocked because quota was exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(buildErrorResponse(429, "QUOTA_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> rateLimitExceeded(RateLimitExceededException ex) {
        log.info("[RATE LIMIT] Request blocked for {}: {}", ex.getIdentifier(), ex.getMessage());
        triggerAlert(
                "SECURITY",
                "RATE_LIMIT_VIOLATION",
                AlertSeverity.WARNING,
                "Ph\u00e1t hi\u1ec7n h\u00e0nh vi spam API t\u1eeb \u0111\u1ed1i t\u01b0\u1ee3ng: " + ex.getIdentifier(),
                null
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(buildErrorResponse(429, "RATE_LIMIT_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandledException(Exception ex) {
        log.error("[SYSTEM FATAL] Unhandled system error: ", ex);
        triggerAlert(
                "SYSTEM_CORE",
                "FATAL_ERROR_500",
                AlertSeverity.CRITICAL,
                "H\u1ec7 th\u1ed1ng ph\u00e1t sinh l\u1ed7i: " + ex.getMessage(),
                null
        );
        notifyCurrentUser(
                NotificationType.SYSTEM_ERROR,
                "L\u1ed7i h\u1ec7 th\u1ed1ng nghi\u00eam tr\u1ecdng (System Error)",
                "H\u1ec7 th\u1ed1ng ph\u00e1t sinh l\u1ed7i: " + ex.getClass().getSimpleName() + " - " + ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse(500, "INTERNAL_SERVER_ERROR", "H\u1ec7 th\u1ed1ng ph\u00e1t sinh l\u1ed7i n\u1ed9i b\u1ed9."));
    }

    private void triggerAlert(String source, String alertType, AlertSeverity severity, String message, JsonNode metadata) {
        AlertService alertService = alertServiceProvider.getIfAvailable();
        if (alertService == null) {
            return;
        }
        try {
            alertService.triggerAlert(source, alertType, severity, message, metadata);
        } catch (Exception alertException) {
            log.error("Failed to trigger alert {}", alertType, alertException);
        }
    }

    private void notifyCurrentUser(NotificationType type, String title, String content) {
        NotificationService notificationService = notificationServiceProvider.getIfAvailable();
        if (notificationService == null) {
            return;
        }
        UUID userId = getCurrentUserId();
        if (userId == null) {
            return;
        }
        try {
            notificationService.createNotification(userId, type, title, content);
        } catch (Exception notificationException) {
            log.error("Failed to save notification {}", type, notificationException);
        }
    }

    private UUID getCurrentUserId() {
        CurrentUserService currentUserService = currentUserServiceProvider.getIfAvailable();
        UserRepository userRepository = userRepositoryProvider.getIfAvailable();
        if (currentUserService == null || userRepository == null) {
            return null;
        }
        try {
            return userRepository.findIdByUsername(currentUserService.getCurrentUsername()).orElse(null);
        } catch (Exception ex) {
            log.debug("Could not resolve current user for notification", ex);
            return null;
        }
    }
}
