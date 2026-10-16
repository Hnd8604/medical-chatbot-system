package com.medicalchatbot.backend.security;

import java.io.IOException;
import java.util.List;

import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying a JWT. The user is reloaded on every request so that a lock,
 * logout or password change takes effect immediately instead of when the token expires.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String NOTIFICATION_STREAM_PATH = "/api/notifications/stream";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final SecurityErrorWriter securityErrorWriter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            // Anonymous request: the authorization rules decide whether it may continue.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            User user = authenticate(token);
            AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal,
                    token,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
            ));
        } catch (AuthenticationFailure failure) {
            securityErrorWriter.write(response, failure.errorCode, failure.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private User authenticate(String token) {
        if (token.isEmpty()) {
            throw AuthenticationFailure.unauthorized("Token không hợp lệ.");
        }
        JwtTokenProvider.TokenClaims claims;
        try {
            claims = jwtTokenProvider.parse(token);
        } catch (ExpiredJwtException exception) {
            throw AuthenticationFailure.unauthorized("Phiên đăng nhập đã hết hạn.");
        } catch (JwtException | IllegalArgumentException exception) {
            throw AuthenticationFailure.unauthorized("Token không hợp lệ.");
        }

        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> AuthenticationFailure.unauthorized("Người dùng không tồn tại."));
        if (user.getTokenVersion() != claims.tokenVersion()) {
            throw AuthenticationFailure.unauthorized("Phiên đăng nhập đã hết hiệu lực.");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw AuthenticationFailure.forbidden("Tài khoản đã bị khóa.");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw AuthenticationFailure.forbidden("Tài khoản đã bị vô hiệu hóa.");
        }
        return user;
    }

    /**
     * Reads the token from {@code Authorization: Bearer ...}. Browsers cannot set headers on an
     * {@code EventSource}, so only the notification stream also accepts {@code ?token=}; keeping it
     * to that endpoint limits tokens showing up in access logs. Returns {@code null} when absent.
     */
    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        if ("GET".equals(request.getMethod()) && request.getRequestURI().endsWith(NOTIFICATION_STREAM_PATH)) {
            String tokenParam = request.getParameter("token");
            return tokenParam == null ? null : tokenParam.trim();
        }
        return null;
    }

    private static final class AuthenticationFailure extends RuntimeException {

        private final ErrorCode errorCode;

        private AuthenticationFailure(ErrorCode errorCode, String message) {
            super(message, null, false, false);
            this.errorCode = errorCode;
        }

        static AuthenticationFailure unauthorized(String message) {
            return new AuthenticationFailure(ErrorCode.AUTHENTICATION_REQUIRED, message);
        }

        static AuthenticationFailure forbidden(String message) {
            return new AuthenticationFailure(ErrorCode.ACCESS_DENIED, message);
        }
    }
}
