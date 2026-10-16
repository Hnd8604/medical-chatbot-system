package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.auth.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.VerifyResetCodeRequest;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        AuditService auditService = new AuditService(auditLogRepository, Mappers.getMapper(AuditLogMapper.class), new ObjectMapper());
        service = new PasswordResetService(userRepository, otpService, emailService, passwordEncoder,
                refreshTokenService, new AuthAuditService(auditService));
        user = TestUsers.user(UserRole.USER);
        user.setEmail("user@demo.local");
        user.setPasswordHash("OLD_HASH");
    }

    @Test
    void unknownEmailGetsTheSameAnswerWithoutACode() {
        when(userRepository.findByEmailIgnoreCase("ghost@demo.local")).thenReturn(Optional.empty());

        assertEquals(PasswordResetService.RESET_CODE_SENT,
                service.sendResetCode(new ForgotPasswordRequest("Ghost@Demo.local")).message());
        verify(otpService, never()).issueCode(anyString());
        verify(emailService, never()).sendPasswordResetCode(anyString(), anyString());
    }

    @Test
    void activeUserGetsACodeByEmailAndTheRequestIsAudited() {
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));
        when(otpService.issueCode("user@demo.local")).thenReturn(Optional.of("123456"));

        assertEquals(PasswordResetService.RESET_CODE_SENT,
                service.sendResetCode(new ForgotPasswordRequest("User@Demo.local")).message());

        verify(emailService).sendPasswordResetCode("user@demo.local", "123456");
        assertEquals("PASSWORD_RESET_REQUEST", capturedAudit().getAction());
    }

    @Test
    void cooldownSendsNothingButAnswersTheSame() {
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));
        when(otpService.issueCode("user@demo.local")).thenReturn(Optional.empty());

        assertEquals(PasswordResetService.RESET_CODE_SENT,
                service.sendResetCode(new ForgotPasswordRequest("user@demo.local")).message());
        verify(emailService, never()).sendPasswordResetCode(anyString(), anyString());
    }

    @Test
    void lockedUserGetsTheSameAnswerWithoutACode() {
        user.setStatus(UserStatus.LOCKED);
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));

        assertEquals(PasswordResetService.RESET_CODE_SENT,
                service.sendResetCode(new ForgotPasswordRequest("user@demo.local")).message());
        verify(otpService, never()).issueCode(anyString());
    }

    @Test
    void verifiedCodeReturnsTheTicket() {
        when(otpService.verifyCode("User@Demo.local", "123456")).thenReturn("jti.secret");

        assertEquals("jti.secret",
                service.verifyResetCode(new VerifyResetCodeRequest("User@Demo.local", "123456")).resetTicket());
    }

    @Test
    void resetUpdatesHashAndEndsEverySession() {
        when(otpService.consumeTicket("jti.secret")).thenReturn("user@demo.local");
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass123")).thenReturn("NEW_HASH");

        service.resetPassword(new ResetPasswordRequest("jti.secret", "NewPass123", "NewPass123"));

        assertEquals("NEW_HASH", user.getPasswordHash());
        assertEquals(1, user.getTokenVersion());
        verify(refreshTokenService).revokeAll(user.getId());
        assertEquals("PASSWORD_RESET_SUCCESS", capturedAudit().getAction());
    }

    @Test
    void weakPasswordDoesNotBurnTheTicket() {
        assertThrows(AppException.class,
                () -> service.resetPassword(new ResetPasswordRequest("jti.secret", "short", "short")));

        verify(otpService, never()).consumeTicket(anyString());
    }

    @Test
    void resetForVanishedUserIsInvalid() {
        when(otpService.consumeTicket("jti.secret")).thenReturn("user@demo.local");
        when(userRepository.findByEmailIgnoreCase("user@demo.local")).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> service.resetPassword(new ResetPasswordRequest("jti.secret", "NewPass123", "NewPass123")));

        assertEquals(ErrorCode.RESET_TICKET_INVALID, exception.getErrorCode());
    }

    private AuditLog capturedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }
}
