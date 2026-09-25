package com.medicalchatbot.backend.exception;

import java.sql.SQLException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.response.ApiErrorResponse;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

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

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> appException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("Typed application failure: {}", errorCode.getErrorCode());
        }
        String publicMessage = errorCode.getStatus().is5xxServerError() ? null : exception.getReason();
        return build(errorCode, publicMessage);
    }

    /** Keeps all existing ResponseStatusException call sites backward compatible. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException exception) {
        ErrorCode errorCode = ErrorCode.fromStatus(exception.getStatusCode());
        String publicMessage = exception.getStatusCode().is5xxServerError() ? null : exception.getReason();
        if (exception.getStatusCode().is5xxServerError()) {
            log.error("Explicit server error returned with HTTP {}", exception.getStatusCode().value());
        } else {
            log.warn("Request rejected with HTTP {}", exception.getStatusCode().value());
        }
        return build(errorCode, publicMessage, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<ApiErrorResponse> externalResourceNotFound(HttpClientErrorException.NotFound exception) {
        log.warn("chatbot-service returned HTTP 404");
        return build(ErrorCode.RESOURCE_NOT_FOUND,
                "Không tìm thấy tài nguyên trong dịch vụ AI.",
                HttpHeaders.EMPTY,
                HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiErrorResponse> externalResponseError(RestClientResponseException exception) {
        HttpStatusCode remoteStatus = exception.getStatusCode();
        // Deliberately do not log or return getResponseBodyAsString(): upstream bodies may contain PHI or internals.
        log.error("chatbot-service returned HTTP {}", remoteStatus.value());

        if (remoteStatus.is4xxClientError()) {
            return build(ErrorCode.CHATBOT_REQUEST_REJECTED);
        }

        triggerAlert(
                "AI_SERVICE",
                "CHATBOT_SERVICE_5XX",
                AlertSeverity.CRITICAL,
                "Dịch vụ AI trả về lỗi HTTP " + remoteStatus.value() + ".",
                null
        );
        notifyCurrentUser(
                NotificationType.SYSTEM_ERROR,
                "Dịch vụ AI gặp lỗi",
                "Dịch vụ AI tạm thời xử lý thất bại. Vui lòng thử lại sau."
        );
        return build(ErrorCode.CHATBOT_SERVICE_ERROR);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> externalServiceUnavailable(RestClientException exception) {
        // Avoid logging the exception message because some clients embed the upstream response body in it.
        log.error("chatbot-service is unavailable ({})", exception.getClass().getSimpleName());
        triggerAlert(
                "AI_SERVICE",
                "CHATBOT_UNAVAILABLE",
                AlertSeverity.CRITICAL,
                "Không thể kết nối tới dịch vụ AI.",
                null
        );
        notifyCurrentUser(
                NotificationType.SYSTEM_ERROR,
                "Không thể kết nối dịch vụ AI",
                "Dịch vụ AI hiện không khả dụng. Vui lòng thử lại sau."
        );
        return build(ErrorCode.CHATBOT_UNAVAILABLE);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            BindException.class
    })
    public ResponseEntity<ApiErrorResponse> validationError(Exception exception) {
        log.debug("Request validation failed ({})", exception.getClass().getSimpleName());
        return build(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> malformedBody(HttpMessageNotReadableException exception) {
        log.debug("Malformed request body ({})", exception.getClass().getSimpleName());
        return build(ErrorCode.MALFORMED_REQUEST);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> typeMismatch(TypeMismatchException exception) {
        String message = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? "Giá trị không hợp lệ cho tham số: " + mismatch.getName()
                : null;
        return build(ErrorCode.MALFORMED_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
        return build(
                ErrorCode.MALFORMED_REQUEST,
                "Thiếu tham số bắt buộc: " + exception.getParameterName(),
                exception.getHeaders(),
                exception.getStatusCode()
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorResponse> missingPart(MissingServletRequestPartException exception) {
        return build(
                ErrorCode.MALFORMED_REQUEST,
                "Thiếu phần dữ liệu bắt buộc: " + exception.getRequestPartName(),
                exception.getHeaders(),
                exception.getStatusCode()
        );
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiErrorResponse> requestBinding(ServletRequestBindingException exception) {
        return build(ErrorCode.MALFORMED_REQUEST, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> endpointNotFound(Exception exception) {
        return build(ErrorCode.ENDPOINT_NOT_FOUND);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> uploadTooLarge(MaxUploadSizeExceededException exception) {
        log.debug("Upload exceeded configured size limit");
        return build(ErrorCode.PAYLOAD_TOO_LARGE, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> mediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> mediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        // The client explicitly rejected JSON, so serializing an ApiErrorResponse would fail again.
        return new ResponseEntity<>(null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> asyncTimeout(
            AsyncRequestTimeoutException exception,
            HttpServletResponse response
    ) {
        log.debug("Async request timed out");
        if (response.isCommitted()) {
            return null;
        }
        return build(ErrorCode.ASYNC_REQUEST_TIMEOUT, null, exception.getHeaders(),
                ErrorCode.ASYNC_REQUEST_TIMEOUT.getStatus());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void asyncResponseNotUsable(AsyncRequestNotUsableException exception) {
        log.debug("Async response is no longer usable ({})", exception.getClass().getSimpleName());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> accessDenied(AccessDeniedException exception) {
        return build(ErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiErrorResponse> quotaExceeded(QuotaExceededException exception) {
        log.info("Request blocked because quota was exceeded");
        return build(ErrorCode.QUOTA_EXCEEDED, exception.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> rateLimitExceeded(RateLimitExceededException exception) {
        log.info("Request blocked by rate limit for {}", exception.getIdentifier());
        triggerAlert(
                "SECURITY",
                "RATE_LIMIT_VIOLATION",
                AlertSeverity.WARNING,
                "Phát hiện hành vi gửi yêu cầu quá nhanh từ: " + exception.getIdentifier(),
                null
        );
        return build(ErrorCode.RATE_LIMIT_EXCEEDED, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> dataIntegrity(DataIntegrityViolationException exception) {
        String sqlState = findSqlState(exception);
        if (UNIQUE_VIOLATION.equals(sqlState) || FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            log.warn("Database conflict (SQL state {})", sqlState);
            return build(ErrorCode.DATA_CONFLICT);
        }
        log.error("Unexpected data integrity failure", exception);
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiErrorResponse> infrastructureFailure(RuntimeException exception) {
        log.error("Database infrastructure failure", exception);
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ConversionNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> conversionFailure(ConversionNotSupportedException exception) {
        log.error("Server-side conversion failure", exception);
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ApiErrorResponse> serializationFailure(HttpMessageNotWritableException exception) {
        log.error("Failed to serialize response", exception);
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponse> malformedMultipart(MultipartException exception) {
        log.debug("Malformed multipart request ({})", exception.getClass().getSimpleName());
        return build(ErrorCode.MALFORMED_REQUEST, "Dữ liệu multipart không đúng định dạng.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalidArgument(IllegalArgumentException exception) {
        log.debug("Invalid application argument ({})", exception.getClass().getSimpleName());
        return build(ErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unhandled(Exception exception) {
        log.error("Unhandled system error", exception);
        triggerAlert(
                "SYSTEM_CORE",
                "FATAL_ERROR_500",
                AlertSeverity.CRITICAL,
                "Hệ thống phát sinh lỗi nội bộ.",
                null
        );
        notifyCurrentUser(
                NotificationType.SYSTEM_ERROR,
                "Lỗi hệ thống",
                "Hệ thống phát sinh lỗi nội bộ. Vui lòng thử lại sau."
        );
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode errorCode) {
        return build(errorCode, null);
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode errorCode, String publicMessage) {
        return build(errorCode, publicMessage, HttpHeaders.EMPTY, errorCode.getStatus());
    }

    private ResponseEntity<ApiErrorResponse> build(
            ErrorCode errorCode,
            String publicMessage,
            HttpHeaders headers,
            HttpStatusCode status
    ) {
        return new ResponseEntity<>(
                ApiErrorResponse.from(errorCode, status.value(), publicMessage),
                headers,
                status
        );
    }

    private String findSqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
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
            return currentUserService.getCurrentUserIdOrNull();
        } catch (Exception exception) {
            log.debug("Could not resolve current user for notification ({})",
                    exception.getClass().getSimpleName());
            return null;
        }
    }
}
