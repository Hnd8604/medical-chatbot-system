package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.AuthLoginRequest;
import com.medicalchatbot.backend.dto.response.AuthLoginResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
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
    private JwtTokenService jwtTokenService;

    @Mock
    private CurrentUserService currentUserService;

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
                "DoctorDemo123!"
        );
        when(userRepository.findByUsernameOrEmailIgnoreCase("doctor_demo")).thenReturn(Optional.of(user));
        when(jwtTokenService.generateToken(user)).thenReturn("jwt-token");
        when(jwtTokenService.expiresInSeconds()).thenReturn(28_800L);

        AuthLoginResponse response = newService().login(new AuthLoginRequest("doctor_demo", "DoctorDemo123!"));

        assertEquals("jwt-token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals("doctor_demo", response.user().username());
        assertEquals(UserRole.DOCTOR, response.user().role());
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
                "UserDemo123!"
        );
        when(userRepository.findByUsernameOrEmailIgnoreCase("user_demo")).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> newService().login(new AuthLoginRequest("user_demo", "wrong-password"))
        );

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
                "UserDemo123!"
        );
        when(userRepository.findByUsernameOrEmailIgnoreCase("locked_user")).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> newService().login(new AuthLoginRequest("locked_user", "UserDemo123!"))
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
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
                "AdminDemo123!"
        );
        ReflectionTestUtils.setField(user, "tokenVersion", 3);
        when(currentUserService.requireCurrentUser()).thenReturn(user);

        newService().logout();

        assertEquals(4, user.getTokenVersion());
        verify(userRepository).save(user);
    }

    private AuthService newService() {
        return new AuthService(
                userRepository,
                passwordEncoder,
                jwtTokenService,
                currentUserService,
                auditLogRepository,
                new ObjectMapper()
        );
    }

    private User user(
            UUID id,
            String username,
            String email,
            String displayName,
            UserRole role,
            UserStatus status,
            String password
    ) {
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
