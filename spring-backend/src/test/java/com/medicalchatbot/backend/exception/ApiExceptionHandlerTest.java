package com.medicalchatbot.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.medicalchatbot.backend.dto.response.ApiErrorResponse;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
class ApiExceptionHandlerTest {

    @Mock
    private ObjectProvider<AlertService> alertServiceProvider;
    @Mock
    private ObjectProvider<NotificationService> notificationServiceProvider;
    @Mock
    private ObjectProvider<CurrentUserService> currentUserServiceProvider;
    @Mock
    private ObjectProvider<UserRepository> userRepositoryProvider;

    private ApiExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler(
                alertServiceProvider,
                notificationServiceProvider,
                currentUserServiceProvider,
                userRepositoryProvider
        );
    }

    @Test
    void upstreamFailureNeverExposesRemoteBody() {
        String sensitiveRemoteBody = "patient=BN2026-00001; internal-stack-trace";
        var exception = new HttpServerErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Remote failure",
                HttpHeaders.EMPTY,
                sensitiveRemoteBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        ResponseEntity<ApiErrorResponse> response = handler.externalResponseError(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(5002);
        assertThat(response.getBody().detail()).doesNotContain(sensitiveRemoteBody, "BN2026-00001");
    }

    @Test
    void typedErrorKeepsStableNumericAndMachineCodes() {
        ResponseEntity<ApiErrorResponse> response = handler.appException(
                new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiên chat.")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(9011);
        assertThat(response.getBody().errorCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Không tìm thấy phiên chat.");
    }
}
