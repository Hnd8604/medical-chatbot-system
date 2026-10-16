package com.medicalchatbot.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import com.medicalchatbot.backend.dto.response.ApiErrorResponse;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private ObjectProvider<AlertService> alertServiceProvider;
    @Mock
    private ObjectProvider<NotificationService> notificationServiceProvider;
    @Mock
    private AlertService alertService;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(alertServiceProvider, notificationServiceProvider);
    }

    @Test
    void upstreamFailureNeverExposesRemoteBody() {
        when(alertServiceProvider.getIfAvailable()).thenReturn(alertService);
        String sensitiveRemoteBody = "patient=BN2026-00001; internal-stack-trace";
        var exception = new HttpServerErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Remote failure",
                HttpHeaders.EMPTY,
                sensitiveRemoteBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        ResponseEntity<ApiErrorResponse> response = handler.chatbotErrorResponse(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(5002);
        assertThat(response.getBody().message()).doesNotContain(sensitiveRemoteBody, "BN2026-00001");
        verify(alertService).raise(eq(AlertType.CHATBOT_SERVICE_5XX), contains("500"), isNull());
    }

    @Test
    void failingAlertDoesNotReplaceTheErrorResponse() {
        when(alertServiceProvider.getIfAvailable()).thenReturn(alertService);
        doThrow(new IllegalStateException("db down")).when(alertService).raise(any(), any(), any());

        ResponseEntity<ApiErrorResponse> response = handler.unhandled(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).doesNotContain("boom");
    }

    @Test
    void upstreamPolicyRejectionMapsToForbiddenWithoutRemoteBody() {
        String remoteBody = "{\"detail\": \"Tài khoản USER không được tìm kiếm danh sách bệnh nhân.\"}";
        var exception = (HttpClientErrorException.Forbidden) HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                HttpHeaders.EMPTY,
                remoteBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        ResponseEntity<ApiErrorResponse> response = handler.chatbotAccessDenied(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo(1102);
        assertThat(response.getBody().errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.getBody().message()).doesNotContain("tìm kiếm danh sách bệnh nhân");
    }

    @Test
    void typedErrorKeepsStableNumericAndMachineCodes() {
        ResponseEntity<ApiErrorResponse> response = handler.appException(
                AppException.notFound("Không tìm thấy phiên chat."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(9011);
        assertThat(response.getBody().errorCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("Không tìm thấy phiên chat.");
    }

    @Test
    void serverSideApplicationErrorHidesItsMessage() {
        ResponseEntity<ApiErrorResponse> response = handler.appException(
                new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "SQL detail that must stay private"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage());
    }
}
