package com.medicalchatbot.backend.exception;

import java.util.Map;
import java.util.UUID;

import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    public ApiExceptionHandler(
            @Lazy NotificationService notificationService,
            @Lazy CurrentUserService currentUserService,
            @Lazy UserRepository userRepository
    ) {
        this.notificationService = notificationService;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    ResponseEntity<Map<String, String>> notFound(HttpClientErrorException.NotFound exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Không tìm thấy tài nguyên trong chatbot-service."));
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<Map<String, String>> chatbotResponseError(RestClientResponseException exception) {
        log.error("Chatbot service returned error: ", exception);

        UUID userId = getCurrentUserId();
        if (userId != null) {
            String title = "Dịch vụ AI trả về lỗi (AI Service Error)";
            String content = "Dịch vụ chatbot-service trả về mã trạng thái: " + exception.getStatusCode();
            try {
                notificationService.createNotification(userId, NotificationType.SYSTEM_ERROR, title, content);
            } catch (Exception e) {
                log.error("Failed to save chatbot error notification", e);
            }
        }

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("detail", "Chatbot-service trả về lỗi."));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, String>> chatbotUnavailable(RestClientException exception) {
        log.error("Chatbot service unavailable: ", exception);

        UUID userId = getCurrentUserId();
        if (userId != null) {
            String title = "Lỗi kết nối dịch vụ AI (AI Service Down)";
            String content = "Không thể kết nối đến chatbot-service: " + exception.getMessage();
            try {
                notificationService.createNotification(userId, NotificationType.SYSTEM_ERROR, title, content);
            } catch (Exception e) {
                log.error("Failed to save chatbot unavailable notification", e);
            }
        }

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("detail", "Chatbot-service hiện không khả dụng."));
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> validationError(Exception exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("detail", "Dữ liệu yêu cầu không hợp lệ."));
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<Map<String, String>> quotaExceeded(QuotaExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("detail", exception.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<Map<String, String>> rateLimitExceeded(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("detail", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnhandledException(Exception exception) {
        log.error("Critical system error caught in ApiExceptionHandler: ", exception);

        UUID userId = getCurrentUserId();
        if (userId != null) {
            String title = "Lỗi hệ thống nghiêm trọng (System Error)";
            String content = "Hệ thống phát sinh lỗi: " + exception.getClass().getSimpleName() + " - " + exception.getMessage();
            try {
                notificationService.createNotification(userId, NotificationType.SYSTEM_ERROR, title, content);
            } catch (Exception e) {
                log.error("Failed to save system error notification", e);
            }
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Hệ thống phát sinh lỗi nội bộ."));
    }

    private UUID getCurrentUserId() {
        String username = currentUserService.getCurrentUsername();
        return userRepository.findIdByUsername(username).orElse(null);
    }
}
