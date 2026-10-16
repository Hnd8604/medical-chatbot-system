package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.auth.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.LoginRequest;
import com.medicalchatbot.backend.dto.request.auth.RefreshTokenRequest;
import com.medicalchatbot.backend.dto.request.auth.RegisterRequest;
import com.medicalchatbot.backend.dto.request.auth.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.response.auth.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.auth.LoginResponse;
import com.medicalchatbot.backend.dto.response.auth.TokenResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.security.JwtTokenProvider;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);

    @Mock
    private UserRepository userRepository;

    @Mock
    private QuotaPolicyRepository quotaPolicyRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private PatientLinkService patientLinkService;

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuthService service;

    @BeforeEach
    void setUp() {
        AuditService auditService = new AuditService(auditLogRepository, Mappers.getMapper(AuditLogMapper.class), new ObjectMapper());
        service = new AuthService(
                userRepository,
                quotaPolicyRepository,
                PASSWORD_ENCODER,
                jwtTokenProvider,
                refreshTokenService,
                patientLinkService,
                Mappers.getMapper(UserMapper.class),
                new AuthAuditService(auditService)
        );
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void loginIssuesTokensAndAuditsSuccess() {
        User user = user(UserRole.DOCTOR, "DoctorDemo123!");
        when(userRepository.findByUsernameOrEmailIgnoreCase("doctor_demo")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateToken(user)).thenReturn("jwt-token");
        when(jwtTokenProvider.expiresInSeconds()).thenReturn(1_800L);
        when(refreshTokenService.issue(user)).thenReturn("refresh-token");

        LoginResponse response = service.login(new LoginRequest(" doctor_demo ", "DoctorDemo123!"));

        assertEquals("jwt-token", response.accessToken());
        assertEquals("refresh-token", response.refreshToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(UserRole.DOCTOR, response.user().role());
        assertEquals("LOGIN_SUCCESS", capturedAudit().getAction());
    }

    @Test
    void loginRejectsWrongPasswordAndAuditsFailure() {
        User user = user(UserRole.USER, "UserDemo123!");
        when(userRepository.findByUsernameOrEmailIgnoreCase("user_demo")).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class,
                () -> service.login(new LoginRequest("user_demo", "wrong-password")));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        AuditLog audit = capturedAudit();
        assertEquals("LOGIN_FAILURE", audit.getAction());
        assertEquals("INVALID_CREDENTIALS", audit.getMetadataJson().path("reason").asText());
    }

    @Test
    void loginRejectsUnknownUserWithTheSameMessage() {
        when(userRepository.findByUsernameOrEmailIgnoreCase("ghost")).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> service.login(new LoginRequest("ghost", "whatever1")));

        assertEquals("Tên đăng nhập/email hoặc mật khẩu không đúng.", exception.getMessage());
    }

    @Test
    void loginRejectsLockedUser() {
        User user = user(UserRole.USER, "UserDemo123!");
        user.setStatus(UserStatus.LOCKED);
        when(userRepository.findByUsernameOrEmailIgnoreCase("user_demo")).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class,
                () -> service.login(new LoginRequest("user_demo", "UserDemo123!")));

        assertEquals(ErrorCode.ACCOUNT_LOCKED, exception.getErrorCode());
        assertEquals("Tài khoản đã bị khóa.", exception.getMessage());
        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void refreshRotatesTokensForActiveUser() {
        User user = user(UserRole.DOCTOR, "DoctorDemo123!");
        when(refreshTokenService.rotate("old-refresh")).thenReturn(new RefreshTokenService.TokenOwner(user.getId(), 0));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateToken(user)).thenReturn("new-access");
        when(jwtTokenProvider.expiresInSeconds()).thenReturn(1_800L);
        when(refreshTokenService.issue(user)).thenReturn("new-refresh");

        TokenResponse response = service.refresh(new RefreshTokenRequest("old-refresh"));

        assertEquals("new-access", response.accessToken());
        assertEquals("new-refresh", response.refreshToken());
    }

    @Test
    void refreshRejectsRevokedSessionWhenTokenVersionMismatch() {
        User user = user(UserRole.DOCTOR, "DoctorDemo123!");
        user.setTokenVersion(5);
        when(refreshTokenService.rotate("stale-refresh")).thenReturn(new RefreshTokenService.TokenOwner(user.getId(), 4));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class,
                () -> service.refresh(new RefreshTokenRequest("stale-refresh")));

        assertEquals(ErrorCode.TOKEN_REVOKED, exception.getErrorCode());
        verify(refreshTokenService).revokeAll(user.getId());
    }

    @Test
    void refreshRejectsLockedUser() {
        User user = user(UserRole.USER, "UserDemo123!");
        user.setStatus(UserStatus.LOCKED);
        when(refreshTokenService.rotate("any-refresh")).thenReturn(new RefreshTokenService.TokenOwner(user.getId(), 0));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class,
                () -> service.refresh(new RefreshTokenRequest("any-refresh")));

        assertEquals(ErrorCode.ACCOUNT_LOCKED, exception.getErrorCode());
    }

    @Test
    void registerCreatesActiveUserOnTheDefaultPolicy() {
        QuotaPolicy policy = QuotaPolicy.builder().name("user_standard").dailyRequestLimit(30)
                .dailyTokenLimit(50000).dailyCostLimitUsd(new BigDecimal("0.50")).build();
        when(userRepository.existsByUsernameIgnoreCase("new_user")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(quotaPolicyRepository.findByName("user_standard")).thenReturn(Optional.of(policy));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(patientLinkService.isOnboardingRequired(any())).thenReturn(true);

        AuthUserResponse response = service.register(new RegisterRequest(
                "Nguyễn Văn A", "New_User", "NEW@example.com", "Password123!", "Password123!"));

        assertEquals("new_user", response.username());
        assertEquals("new@example.com", response.email());
        assertEquals(UserRole.USER, response.role());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertTrue(response.onboardingRequired());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertEquals(policy, saved.getValue().getQuotaPolicy());
        assertTrue(PASSWORD_ENCODER.matches("Password123!", saved.getValue().getPasswordHash()));
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsernameIgnoreCase("new_user")).thenReturn(true);

        AppException exception = assertThrows(AppException.class, () -> service.register(new RegisterRequest(
                "Nguyễn Văn A", "new_user", "new@example.com", "Password123!", "Password123!")));

        assertEquals(ErrorCode.USERNAME_EXISTED, exception.getErrorCode());
    }

    @Test
    void registerRejectsWeakPassword() {
        AppException exception = assertThrows(AppException.class, () -> service.register(new RegisterRequest(
                "Nguyễn Văn A", "new_user", "new@example.com", "password", "password")));

        assertEquals(ErrorCode.PASSWORD_TOO_WEAK, exception.getErrorCode());
    }

    @Test
    void currentUserReportsOnboardingState() {
        User user = user(UserRole.USER, "Password123!");
        TestUsers.signIn(user, userRepository);
        when(patientLinkService.isOnboardingRequired(user)).thenReturn(true);

        assertTrue(service.currentUser().onboardingRequired());
    }

    @Test
    void logoutEndsEverySession() {
        User user = user(UserRole.ADMIN, "AdminDemo123!");
        user.setTokenVersion(3);
        TestUsers.signIn(user, userRepository);

        service.logout();

        assertEquals(4, user.getTokenVersion());
        verify(userRepository).save(user);
        verify(refreshTokenService).revokeAll(user.getId());
        assertEquals("LOGOUT", capturedAudit().getAction());
    }

    @Test
    void updateProfileNormalizesDisplayNameAndEmail() {
        User user = user(UserRole.USER, "UserDemo123!");
        TestUsers.signIn(user, userRepository);
        when(userRepository.existsByEmailIgnoreCase("new_mail@example.com")).thenReturn(false);

        AuthUserResponse response = service.updateProfile(new UpdateProfileRequest("  Tên Mới  ", "NEW_MAIL@example.com"));

        assertEquals("Tên Mới", response.displayName());
        assertEquals("new_mail@example.com", response.email());
        verify(userRepository).saveAndFlush(user);
        assertTrue(capturedAudit().getMetadataJson().path("email_changed").asBoolean());
    }

    @Test
    void updateProfileRejectsDuplicateEmail() {
        TestUsers.signIn(user(UserRole.USER, "UserDemo123!"), userRepository);
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        AppException exception = assertThrows(AppException.class,
                () -> service.updateProfile(new UpdateProfileRequest("User Demo", "taken@example.com")));

        assertEquals(ErrorCode.EMAIL_EXISTED, exception.getErrorCode());
    }

    @Test
    void updateProfileAllowsKeepingOwnEmail() {
        User user = user(UserRole.USER, "UserDemo123!");
        TestUsers.signIn(user, userRepository);

        AuthUserResponse response = service.updateProfile(
                new UpdateProfileRequest("Tên Khác", user.getEmail().toUpperCase()));

        assertEquals(user.getEmail(), response.email());
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        assertFalse(capturedAudit().getMetadataJson().path("email_changed").asBoolean());
    }

    @Test
    void changePasswordUpdatesHashAndEndsEverySession() {
        User user = user(UserRole.USER, "OldPass123!");
        user.setTokenVersion(2);
        TestUsers.signIn(user, userRepository);

        service.changePassword(new ChangePasswordRequest("OldPass123!", "NewPass456!", "NewPass456!"));

        assertTrue(PASSWORD_ENCODER.matches("NewPass456!", user.getPasswordHash()));
        assertEquals(3, user.getTokenVersion());
        verify(refreshTokenService).revokeAll(user.getId());
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordAndAuditsIt() {
        TestUsers.signIn(user(UserRole.USER, "OldPass123!"), userRepository);

        AppException exception = assertThrows(AppException.class, () -> service.changePassword(
                new ChangePasswordRequest("WrongPass123!", "NewPass456!", "NewPass456!")));

        assertEquals(ErrorCode.WRONG_CURRENT_PASSWORD, exception.getErrorCode());
        assertEquals("PASSWORD_CHANGE_FAILURE", capturedAudit().getAction());
    }

    @Test
    void changePasswordRejectsSameAsCurrentPassword() {
        TestUsers.signIn(user(UserRole.USER, "OldPass123!"), userRepository);

        AppException exception = assertThrows(AppException.class, () -> service.changePassword(
                new ChangePasswordRequest("OldPass123!", "OldPass123!", "OldPass123!")));

        assertEquals(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT, exception.getErrorCode());
    }

    private static User user(UserRole role, String password) {
        User user = TestUsers.user(UUID.randomUUID(), role.name().toLowerCase() + "_demo", role);
        user.setPasswordHash(PASSWORD_ENCODER.encode(password));
        return user;
    }

    private AuditLog capturedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }
}
