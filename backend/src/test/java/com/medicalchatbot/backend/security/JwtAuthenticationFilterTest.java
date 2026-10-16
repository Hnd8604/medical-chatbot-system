package com.medicalchatbot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.support.TestUsers;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

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
    void validTokenAuthenticatesRequestWithRoleAuthority() throws Exception {
        User user = user(UserRole.DOCTOR, UserStatus.ACTIVE, 2);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletResponse response = runFilter(bearer(tokenProvider(480).generateToken(user)), tokenProvider(480));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(AuthenticatedUser.current()).contains(new AuthenticatedUser(user.getId(), user.getUsername(), UserRole.DOCTOR));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(authority -> "ROLE_DOCTOR".equals(authority.getAuthority()));
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void staleTokenVersionReturnsUnauthorized() throws Exception {
        User tokenUser = user(UserRole.ADMIN, UserStatus.ACTIVE, 1);
        User currentUser = user(UserRole.ADMIN, UserStatus.ACTIVE, 2);
        currentUser.setId(tokenUser.getId());
        when(userRepository.findById(tokenUser.getId())).thenReturn(Optional.of(currentUser));

        MockHttpServletResponse response = runFilter(bearer(tokenProvider(480).generateToken(tokenUser)), tokenProvider(480));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"code\":1101")
                .contains("Phiên đăng nhập đã hết hiệu lực.");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void lockedUserReturnsForbidden() throws Exception {
        User user = user(UserRole.USER, UserStatus.LOCKED, 0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletResponse response = runFilter(bearer(tokenProvider(480).generateToken(user)), tokenProvider(480));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":1102").contains("Tài khoản đã bị khóa.");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void expiredTokenReturnsUnauthorized() throws Exception {
        String expiredToken = tokenProvider(-1).generateToken(user(UserRole.DOCTOR, UserStatus.ACTIVE, 0));

        MockHttpServletResponse response = runFilter(bearer(expiredToken), tokenProvider(480));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Phiên đăng nhập đã hết hạn.");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void malformedTokenReturnsUnauthorized() throws Exception {
        MockHttpServletResponse response = runFilter(bearer("not-a-jwt"), tokenProvider(480));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Token không hợp lệ.");
    }

    @Test
    void queryParamTokenAuthenticatesSseStream() throws Exception {
        User user = user(UserRole.DOCTOR, UserStatus.ACTIVE, 3);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications/stream");
        request.addParameter("token", tokenProvider(480).generateToken(user));

        MockHttpServletResponse response = runFilter(request, tokenProvider(480));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(AuthenticatedUser.current()).isPresent();
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void queryParamTokenIgnoredOnNonStreamPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications");
        request.addParameter("token", tokenProvider(480).generateToken(user(UserRole.DOCTOR, UserStatus.ACTIVE, 0)));

        MockHttpServletResponse response = runFilter(request, tokenProvider(480));

        // Neither a header nor the stream path: the request continues anonymously.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(any(), any());
    }

    private static MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private MockHttpServletResponse runFilter(MockHttpServletRequest request, JwtTokenProvider tokenProvider) throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                tokenProvider, userRepository, new SecurityErrorWriter(new ObjectMapper()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private static JwtTokenProvider tokenProvider(long expirationMinutes) {
        return new JwtTokenProvider(new JwtProperties(SECRET, expirationMinutes, 10080));
    }

    private static User user(UserRole role, UserStatus status, int tokenVersion) {
        User user = TestUsers.user(UUID.randomUUID(), role.name().toLowerCase() + "_demo", role);
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
