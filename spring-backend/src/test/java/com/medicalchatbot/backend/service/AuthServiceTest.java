package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.AuthLoginRequest;
import com.medicalchatbot.backend.dto.request.AuthLinkPatientRequest;
import com.medicalchatbot.backend.dto.request.AuthRefreshRequest;
import com.medicalchatbot.backend.dto.request.AuthRegisterRequest;
import com.medicalchatbot.backend.dto.request.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.response.AuthLoginResponse;
import com.medicalchatbot.backend.dto.response.AuthRefreshResponse;
import com.medicalchatbot.backend.dto.response.AuthRegisterResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

        @Mock
        private UserRepository userRepository;

        @Mock
        private UserPatientLinkRepository userPatientLinkRepository;

        @Mock
        private QuotaPolicyRepository quotaPolicyRepository;

        @Mock
        private JwtTokenService jwtTokenService;

        @Mock
        private RefreshTokenService refreshTokenService;

        @Mock
        private CurrentUserService currentUserService;

        @Mock
        private ChatbotServiceClient chatbotServiceClient;

        @Mock
        private AuditLogRepository auditLogRepository;

        private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

        @Test
        void loginReturnsTokenForActiveUser() {
                User user = user(
                                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "doctor_demo",
                                "doctor_demo@medical-chatbot.local",
                                "Doctor Demo",
                                UserRole.DOCTOR,
                                UserStatus.ACTIVE,
                                "DoctorDemo123!");
                when(userRepository.findByUsernameOrEmailIgnoreCase("doctor_demo")).thenReturn(Optional.of(user));
                when(jwtTokenService.generateToken(user)).thenReturn("jwt-token");
                when(jwtTokenService.expiresInSeconds()).thenReturn(1_800L);
                when(refreshTokenService.issue(user)).thenReturn("refresh-token");

                AuthLoginResponse response = newService().login(new AuthLoginRequest("doctor_demo", "DoctorDemo123!"));

                assertEquals("jwt-token", response.accessToken());
                assertEquals("refresh-token", response.refreshToken());
                assertEquals("Bearer", response.tokenType());
                assertEquals("doctor_demo", response.user().username());
                assertEquals(UserRole.DOCTOR, response.user().role());
        }

        @Test
        void refreshRotatesTokensForActiveUser() {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                User user = user(
                                userId,
                                "doctor_demo",
                                "doctor_demo@medical-chatbot.local",
                                "Doctor Demo",
                                UserRole.DOCTOR,
                                UserStatus.ACTIVE,
                                "DoctorDemo123!");
                when(refreshTokenService.rotate("old-refresh"))
                                .thenReturn(new RefreshTokenService.RotationResult(userId, 0));
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));
                when(jwtTokenService.generateToken(user)).thenReturn("new-access");
                when(jwtTokenService.expiresInSeconds()).thenReturn(1_800L);
                when(refreshTokenService.issue(user)).thenReturn("new-refresh");

                AuthRefreshResponse response = newService().refresh(new AuthRefreshRequest("old-refresh"));

                assertEquals("new-access", response.accessToken());
                assertEquals("new-refresh", response.refreshToken());
                assertEquals("Bearer", response.tokenType());
        }

        @Test
        void refreshRejectsRevokedSessionWhenTokenVersionMismatch() {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000402");
                User user = user(
                                userId,
                                "doctor_demo",
                                "doctor_demo@medical-chatbot.local",
                                "Doctor Demo",
                                UserRole.DOCTOR,
                                UserStatus.ACTIVE,
                                "DoctorDemo123!");
                ReflectionTestUtils.setField(user, "tokenVersion", 5);
                when(refreshTokenService.rotate("stale-refresh"))
                                .thenReturn(new RefreshTokenService.RotationResult(userId, 4));
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().refresh(new AuthRefreshRequest("stale-refresh")));

                assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
                verify(refreshTokenService).revokeAllForUser(userId);
        }

        @Test
        void refreshRejectsLockedUser() {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000403");
                User user = user(
                                userId,
                                "locked_user",
                                "locked@medical-chatbot.local",
                                "Locked User",
                                UserRole.USER,
                                UserStatus.LOCKED,
                                "UserDemo123!");
                when(refreshTokenService.rotate("any-refresh"))
                                .thenReturn(new RefreshTokenService.RotationResult(userId, 0));
                when(userRepository.findById(userId)).thenReturn(Optional.of(user));

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().refresh(new AuthRefreshRequest("any-refresh")));

                assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        void loginRejectsWrongPassword() {
                User user = user(
                                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                                "user_demo",
                                "user_demo@medical-chatbot.local",
                                "User Demo",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "UserDemo123!");
                when(userRepository.findByUsernameOrEmailIgnoreCase("user_demo")).thenReturn(Optional.of(user));

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().login(new AuthLoginRequest("user_demo", "wrong-password")));

                assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        }

        @Test
        void loginRejectsLockedUser() {
                User user = user(
                                UUID.fromString("00000000-0000-0000-0000-000000000303"),
                                "locked_user",
                                "locked@medical-chatbot.local",
                                "Locked User",
                                UserRole.USER,
                                UserStatus.LOCKED,
                                "UserDemo123!");
                when(userRepository.findByUsernameOrEmailIgnoreCase("locked_user")).thenReturn(Optional.of(user));

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().login(new AuthLoginRequest("locked_user", "UserDemo123!")));

                assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        void registerCreatesActiveUserWithUserDemoQuota() {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000305");
                when(userRepository.existsByUsernameIgnoreCase("new_user")).thenReturn(false);
                when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
                when(quotaPolicyRepository.findByName("user_standard")).thenReturn(Optional.of(quotaPolicy()));
                when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
                        User saved = invocation.getArgument(0);
                        ReflectionTestUtils.setField(saved, "id", userId);
                        return saved;
                });
                when(userPatientLinkRepository.findPatientIdsForUser(userId)).thenReturn(List.of());

                AuthRegisterResponse response = newService().register(new AuthRegisterRequest(
                                "Nguyễn Văn A",
                                "New_User",
                                "NEW@example.com",
                                "Password123!",
                                "Password123!"));

                assertEquals("Đăng ký tài khoản thành công.", response.message());
                assertEquals("new_user", response.user().username());
                assertEquals("new@example.com", response.user().email());
                assertEquals(UserRole.USER, response.user().role());
                assertEquals(UserStatus.ACTIVE, response.user().status());
                assertTrue(response.user().onboardingRequired());
        }

        @Test
        void registerRejectsDuplicateUsername() {
                when(userRepository.existsByUsernameIgnoreCase("new_user")).thenReturn(true);

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().register(new AuthRegisterRequest(
                                                "Nguyễn Văn A",
                                                "new_user",
                                                "new@example.com",
                                                "Password123!",
                                                "Password123!")));

                assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        void registerRejectsWeakPassword() {
                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().register(new AuthRegisterRequest(
                                                "Nguyễn Văn A",
                                                "new_user",
                                                "new@example.com",
                                                "password",
                                                "password")));

                assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        void currentUserMarksUserWithoutPatientLinkAsOnboardingRequired() {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000306");
                User user = user(
                                userId,
                                "new_user",
                                "new@example.com",
                                "New User",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "Password123!");
                when(currentUserService.requireCurrentUser()).thenReturn(user);
                when(userPatientLinkRepository.findPatientIdsForUser(userId)).thenReturn(List.of());

                assertTrue(newService().currentUser().onboardingRequired());
        }

        @Test
        void linkPatientCreatesSelfPrimaryLinkWhenVerificationMatches() throws Exception {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000307");
                User user = user(
                                userId,
                                "new_user",
                                "new@example.com",
                                "New User",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "Password123!");
                when(currentUserService.requireCurrentUser()).thenReturn(user);
                when(userPatientLinkRepository.findSelfLinksForUser(userId)).thenReturn(List.of());
                when(chatbotServiceClient.getPatient("BN2026-00001")).thenReturn(new ObjectMapper().readTree("""
                                {
                                  "id": "BN2026-00001",
                                  "birth_date": "2003-01-01",
                                  "phone": "0900000001"
                                }
                                """));
                when(userPatientLinkRepository.existsSelfLinkForOtherUser("BN2026-00001", userId))
                                .thenReturn(false);
                when(userPatientLinkRepository.saveAndFlush(any(UserPatientLink.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(userPatientLinkRepository.findPatientIdsForUser(userId)).thenReturn(List.of("BN2026-00001"));

                assertEquals(
                                "Liên kết hồ sơ bệnh nhân thành công.",
                                newService().linkPatient(new AuthLinkPatientRequest(
                                                "Patient/BN2026-00001",
                                                "2003-01-01",
                                                "0900000001")).message());
        }

        @Test
        void linkPatientRejectsMismatchedVerification() throws Exception {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000308");
                User user = user(
                                userId,
                                "new_user",
                                "new@example.com",
                                "New User",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "Password123!");
                when(currentUserService.requireCurrentUser()).thenReturn(user);
                when(userPatientLinkRepository.findSelfLinksForUser(userId)).thenReturn(List.of());
                when(chatbotServiceClient.getPatient("BN2026-00001")).thenReturn(new ObjectMapper().readTree("""
                                {
                                  "id": "BN2026-00001",
                                  "birth_date": "2003-01-01",
                                  "phone": "0900000001"
                                }
                                """));

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().linkPatient(new AuthLinkPatientRequest(
                                                "BN2026-00001",
                                                "2004-01-01",
                                                "0900000001")));

                assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        }

        @Test
        void logoutIncrementsTokenVersion() {
                User user = user(
                                UUID.fromString("00000000-0000-0000-0000-000000000304"),
                                "admin_demo",
                                "admin_demo@medical-chatbot.local",
                                "Admin Demo",
                                UserRole.ADMIN,
                                UserStatus.ACTIVE,
                                "AdminDemo123!");
                ReflectionTestUtils.setField(user, "tokenVersion", 3);
                when(currentUserService.requireCurrentUser()).thenReturn(user);

                newService().logout();

                assertEquals(4, user.getTokenVersion());
                verify(userRepository).save(user);
                verify(refreshTokenService).revokeAllForUser(user.getId());
        }

        @Test
        void changePasswordUpdatesHashAndRevokesSessions() {
                UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000501");
                User user = user(
                                userId,
                                "user_demo",
                                "user_demo@medical-chatbot.local",
                                "User Demo",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "OldPass123!");
                ReflectionTestUtils.setField(user, "tokenVersion", 2);
                when(currentUserService.requireCurrentUser()).thenReturn(user);

                newService().changePassword(new ChangePasswordRequest("OldPass123!", "NewPass456!", "NewPass456!"));

                assertTrue(passwordEncoder.matches("NewPass456!", user.getPasswordHash()));
                assertEquals(3, user.getTokenVersion());
                verify(userRepository).save(user);
                verify(refreshTokenService).revokeAllForUser(userId);
        }

        @Test
        void changePasswordRejectsWrongCurrentPassword() {
                User user = user(
                                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                                "user_demo",
                                "user_demo@medical-chatbot.local",
                                "User Demo",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "OldPass123!");
                when(currentUserService.requireCurrentUser()).thenReturn(user);

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().changePassword(
                                                new ChangePasswordRequest("WrongPass123!", "NewPass456!", "NewPass456!")));

                assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        void changePasswordRejectsSameAsCurrentPassword() {
                User user = user(
                                UUID.fromString("00000000-0000-0000-0000-000000000503"),
                                "user_demo",
                                "user_demo@medical-chatbot.local",
                                "User Demo",
                                UserRole.USER,
                                UserStatus.ACTIVE,
                                "OldPass123!");
                when(currentUserService.requireCurrentUser()).thenReturn(user);

                ResponseStatusException ex = assertThrows(
                                ResponseStatusException.class,
                                () -> newService().changePassword(
                                                new ChangePasswordRequest("OldPass123!", "OldPass123!", "OldPass123!")));

                assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        private AuthService newService() {
                return new AuthService(
                                userRepository,
                                userPatientLinkRepository,
                                quotaPolicyRepository,
                                passwordEncoder,
                                jwtTokenService,
                                refreshTokenService,
                                currentUserService,
                                chatbotServiceClient,
                                auditLogRepository,
                                new ObjectMapper());
        }

        private QuotaPolicy quotaPolicy() {
                return new QuotaPolicy("user_standard", 30, 50000, new BigDecimal("0.50"), 10);
        }

        private User user(
                        UUID id,
                        String username,
                        String email,
                        String displayName,
                        UserRole role,
                        UserStatus status,
                        String password) {
                User user = new User(id);
                ReflectionTestUtils.setField(user, "username", username);
                ReflectionTestUtils.setField(user, "email", email);
                ReflectionTestUtils.setField(user, "displayName", displayName);
                ReflectionTestUtils.setField(user, "role", role);
                ReflectionTestUtils.setField(user, "status", status);
                ReflectionTestUtils.setField(user, "passwordHash", passwordEncoder.encode(password));
                ReflectionTestUtils.setField(user, "tokenVersion", 0);
                return user;
        }
}
