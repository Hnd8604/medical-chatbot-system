package com.medicalchatbot.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.JwtTokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789ABCDEF0123456789ABCDEF";

    @Mock
    private UserRepository userRepository;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesRequest() throws Exception {
        User user = user(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                "doctor_demo",
                UserRole.DOCTOR,
                UserStatus.ACTIVE,
                2
        );
        String token = tokenService(480).generateToken(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletResponse response = runFilter(token, tokenService(480));

        assertEquals(200, response.getStatus());
        assertInstanceOf(
                AuthenticatedUser.class,
                SecurityContextHolder.getContext().getAuthentication().getPrincipal()
        );
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_DOCTOR".equals(authority.getAuthority())));
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleTokenVersionReturnsUnauthorized() throws Exception {
        User tokenUser = user(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                "admin_demo",
                UserRole.ADMIN,
                UserStatus.ACTIVE,
                1
        );
        User currentUser = user(tokenUser.getId(), "admin_demo", UserRole.ADMIN, UserStatus.ACTIVE, 2);
        String token = tokenService(480).generateToken(tokenUser);
        when(userRepository.findById(tokenUser.getId())).thenReturn(Optional.of(currentUser));

        MockHttpServletResponse response = runFilter(token, tokenService(480));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Phien dang nhap da het hieu luc."));
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lockedUserReturnsForbidden() throws Exception {
        User user = user(
                UUID.fromString("00000000-0000-0000-0000-000000000503"),
                "user_demo",
                UserRole.USER,
                UserStatus.LOCKED,
                0
        );
        String token = tokenService(480).generateToken(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletResponse response = runFilter(token, tokenService(480));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Tai khoan da bi khoa."));
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredTokenReturnsUnauthorized() throws Exception {
        User user = user(
                UUID.fromString("00000000-0000-0000-0000-000000000504"),
                "doctor_demo",
                UserRole.DOCTOR,
                UserStatus.ACTIVE,
                0
        );
        String expiredToken = tokenService(-1).generateToken(user);

        MockHttpServletResponse response = runFilter(expiredToken, tokenService(480));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Phien dang nhap da het han."));
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private MockHttpServletResponse runFilter(String token, JwtTokenService jwtTokenService) throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenService,
                userRepository,
                new SecurityErrorWriter(new ObjectMapper())
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private JwtTokenService tokenService(long expirationMinutes) {
        return new JwtTokenService(new JwtProperties(SECRET, expirationMinutes));
    }

    private User user(UUID id, String username, UserRole role, UserStatus status, int tokenVersion) {
        User user = new User(id);
        ReflectionTestUtils.setField(user, "username", username);
        ReflectionTestUtils.setField(user, "email", username + "@medical-chatbot.local");
        ReflectionTestUtils.setField(user, "displayName", username);
        ReflectionTestUtils.setField(user, "role", role);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "passwordHash", "hash");
        ReflectionTestUtils.setField(user, "tokenVersion", tokenVersion);
        return user;
    }
}
