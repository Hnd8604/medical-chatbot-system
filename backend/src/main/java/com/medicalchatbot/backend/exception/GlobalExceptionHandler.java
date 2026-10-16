package com.medicalchatbot.backend.exception;

import java.sql.SQLException;

import com.medicalchatbot.backend.dto.response.ApiErrorResponse;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.security.AuthenticatedUser;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
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

/**
 * Translates every exception into an {@link ApiErrorResponse}.
 *
 * <p>Messages of 5xx errors are never exposed, and upstream response bodies are never logged or
 * returned because they may contain PHI. Infrastructure failures additionally raise an operational
 * alert and notify the affected user.</p>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    // Resolved lazily: MVC test slices load this advice without the persistence layer.
    private final ObjectProvider<AlertService> alertService;
    private final ObjectProvider<NotificationService> notificationService;

    // ----- Application errors -----

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> appException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("Application failure: {}", errorCode.getKey());
            return build(errorCode);
        }
        return build(errorCode, exception.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> rateLimitExceeded(RateLimitExceededException exception) {
        log.info("Request blocked by rate limit for {}", exception.getIdentifier());
        raiseAlert(AlertType.RATE_LIMIT_VIOLATION,
                "Phát hiện hành vi gửi yêu cầu quá nhanh từ: " + exception.getIdentifier());
        return build(ErrorCode.RATE_LIMIT_EXCEEDED, exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        if (status.is5xxServerError()) {
            log.error("Request failed with HTTP {}", status.value());
            return build(ErrorCode.fromStatus(status), null, exception.getHeaders(), status);
        }
        log.warn("Request rejected with HTTP {}", status.value());
        return build(ErrorCode.fromStatus(status), exception.getReason(), exception.getHeaders(), status);
    }

    // ----- chatbot-service -----

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<ApiErrorResponse> chatbotResourceNotFound(HttpClientErrorException.NotFound exception) {
        log.warn("chatbot-service returned HTTP 404");
        return build(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tài nguyên trong dịch vụ AI.");
    }

    /** chatbot-service answers 403 when the agent blocks a turn on its role policy. */
    @ExceptionHandler(HttpClientErrorException.Forbidden.class)
    public ResponseEntity<ApiErrorResponse> chatbotAccessDenied(HttpClientErrorException.Forbidden exception) {
        log.warn("chatbot-service returned HTTP 403");
        return build(ErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiErrorResponse> chatbotErrorResponse(RestClientResponseException exception) {
        HttpStatusCode remoteStatus = exception.getStatusCode();
        log.error("chatbot-service returned HTTP {}", remoteStatus.value());
        if (remoteStatus.is4xxClientError()) {
            return build(ErrorCode.CHATBOT_REQUEST_REJECTED);
        }
        raiseAlert(AlertType.CHATBOT_SERVICE_5XX, "Dịch vụ AI trả về lỗi HTTP " + remoteStatus.value() + ".");
        notifyCurrentUser("Dịch vụ AI gặp lỗi", "Dịch vụ AI tạm thời xử lý thất bại. Vui lòng thử lại sau.");
        return build(ErrorCode.CHATBOT_SERVICE_ERROR);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> chatbotUnavailable(RestClientException exception) {
        // The message may embed the upstream body, so only the exception type is logged.
        log.error("chatbot-service is unavailable ({})", exception.getClass().getSimpleName());
        raiseAlert(AlertType.CHATBOT_UNAVAILABLE, "Không thể kết nối tới dịch vụ AI.");
        notifyCurrentUser("Không thể kết nối dịch vụ AI", "Dịch vụ AI hiện không khả dụng. Vui lòng thử lại sau.");
        return build(ErrorCode.CHATBOT_UNAVAILABLE);
    }

    // ----- Invalid requests -----

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
        return build(ErrorCode.MALFORMED_REQUEST, "Thiếu tham số bắt buộc: " + exception.getParameterName(),
                exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorResponse> missingPart(MissingServletRequestPartException exception) {
        return build(ErrorCode.MALFORMED_REQUEST, "Thiếu phần dữ liệu bắt buộc: " + exception.getRequestPartName(),
                exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiErrorResponse> requestBinding(ServletRequestBindingException exception) {
        return build(ErrorCode.MALFORMED_REQUEST, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponse> malformedMultipart(MultipartException exception) {
        return build(ErrorCode.MALFORMED_REQUEST, "Dữ liệu multipart không đúng định dạng.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalidArgument(IllegalArgumentException exception) {
        log.debug("Invalid argument ({})", exception.getClass().getSimpleName());
        return build(ErrorCode.INVALID_ARGUMENT);
    }

    // ----- HTTP protocol -----

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> endpointNotFound(Exception exception) {
        return build(ErrorCode.ENDPOINT_NOT_FOUND);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> mediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> mediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        // The client explicitly rejected JSON, so serializing an error body would fail again.
        return new ResponseEntity<>(null, exception.getHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> asyncTimeout(AsyncRequestTimeoutException exception, HttpServletResponse response) {
        if (response.isCommitted()) {
            return null;
        }
        return build(ErrorCode.ASYNC_REQUEST_TIMEOUT, null, exception.getHeaders(), ErrorCode.ASYNC_REQUEST_TIMEOUT.getStatus());
    }

    /** The client went away (typically a closed SSE stream); there is nobody left to answer. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void asyncResponseNotUsable(AsyncRequestNotUsableException exception) {
        log.debug("Async response is no longer usable");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> accessDenied(AccessDeniedException exception) {
        return build(ErrorCode.ACCESS_DENIED);
    }

    // ----- Server-side failures -----

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

    @ExceptionHandler({
            DataAccessException.class,
            TransactionException.class,
            ConversionNotSupportedException.class,
            HttpMessageNotWritableException.class
    })
    public ResponseEntity<ApiErrorResponse> infrastructureFailure(Exception exception) {
        log.error("Infrastructure failure", exception);
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unhandled(Exception exception) {
        log.error("Unhandled system error", exception);
        raiseAlert(AlertType.FATAL_ERROR_500, "Hệ thống phát sinh lỗi nội bộ.");
        notifyCurrentUser("Lỗi hệ thống", "Hệ thống phát sinh lỗi nội bộ. Vui lòng thử lại sau.");
        return build(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // ----- Helpers -----

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
        return new ResponseEntity<>(ApiErrorResponse.of(errorCode, status.value(), publicMessage), headers, status);
    }

    private static String findSqlState(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /** Side effects of error reporting must never replace the original error response. */
    private void raiseAlert(AlertType type, String message) {
        AlertService alerts = alertService.getIfAvailable();
        if (alerts == null) {
            return;
        }
        try {
            alerts.raise(type, message, null);
        } catch (RuntimeException alertFailure) {
            log.error("Failed to raise alert {}", type, alertFailure);
        }
    }

    private void notifyCurrentUser(String title, String content) {
        NotificationService notifications = notificationService.getIfAvailable();
        if (notifications == null) {
            return;
        }
        AuthenticatedUser.current().ifPresent(user -> {
            try {
                notifications.notify(user.id(), NotificationType.SYSTEM_ERROR, title, content);
            } catch (RuntimeException notificationFailure) {
                log.error("Failed to notify user about a system error", notificationFailure);
            }
        });
    }
}
