package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.VerifyResetCodeResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetOtpService otpService;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PasswordResetService service;

    private User activeUser(String email) {
        return User.builder()
                .id(UUID.randomUUID())
                .username("user_demo")
                .email(email)
                .displayName("User Demo")
                .passwordHash("OLD_HASH")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(0)
                .build();
    }

    @Test
    void forgotPasswordUnknownEmailReturnsNotFound() {
        when(userRepository.findByEmailIgnoreCase("ghost@demo.local")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.forgotPassword(new ForgotPasswordRequest("Ghost@Demo.local"))
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(otpService, never()).issueCode(anyString());
        verify(emailService, never()).sendPasswordResetCode(anyString(), anyString());
    }

    @Test
    void forgotPasswordActiveUserIssuesCodeAndSendsMail() {
        User user = activeUser("user@demo.local");
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));
        when(otpService.issueCode("user@demo.local")).thenReturn(Optional.of("123456"));

        service.forgotPassword(new ForgotPasswordRequest("User@Demo.local"));

        verify(emailService).sendPasswordResetCode("user@demo.local", "123456");
        verify(auditLogRepository).save(eq(user), isNull(), eq("PASSWORD_RESET_REQUEST"), anyString(), anyString(), any());
    }

    @Test
    void forgotPasswordLockedUserIsRejectedAndDoesNotIssueCode() {
        User user = activeUser("user@demo.local");
        user.updateStatus(UserStatus.LOCKED);
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.forgotPassword(new ForgotPasswordRequest("user@demo.local"))
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(otpService, never()).issueCode(anyString());
        verify(emailService, never()).sendPasswordResetCode(anyString(), anyString());
    }

    @Test
    void verifyResetCodeReturnsTicketFromOtpService() {
        when(otpService.verifyCode("user@demo.local", "123456")).thenReturn("jti.secret");

        VerifyResetCodeResponse response =
                service.verifyResetCode(new VerifyResetCodeRequest("User@Demo.local", "123456"));

        assertEquals("jti.secret", response.resetTicket());
    }

    @Test
    void resetPasswordUpdatesHashBumpsTokenVersionAndRevokesSessions() {
        User user = activeUser("user@demo.local");
        when(otpService.consumeTicket("jti.secret")).thenReturn("user@demo.local");
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass123")).thenReturn("NEW_HASH");

        service.resetPassword(new ResetPasswordRequest("jti.secret", "NewPass123", "NewPass123"));

        assertEquals("NEW_HASH", user.getPasswordHash());
        assertEquals(1, user.getTokenVersion());
        verify(userRepository).save(user);
        verify(refreshTokenService).revokeAllForUser(user.getId());
        verify(auditLogRepository).save(eq(user), isNull(), eq("PASSWORD_RESET_SUCCESS"), anyString(), anyString(), any());
    }

    @Test
    void resetPasswordWithWeakPasswordDoesNotConsumeTicket() {
        assertThrows(
                ResponseStatusException.class,
                () -> service.resetPassword(new ResetPasswordRequest("jti.secret", "short", "short"))
        );
        verify(otpService, never()).consumeTicket(anyString());
    }

    @Test
    void resetPasswordRejectsWhenUserMissing() {
        when(otpService.consumeTicket("jti.secret")).thenReturn("user@demo.local");
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.resetPassword(new ResetPasswordRequest("jti.secret", "NewPass123", "NewPass123"))
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
