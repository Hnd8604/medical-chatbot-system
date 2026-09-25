package com.medicalchatbot.backend.config;

import java.io.IOException;
import java.util.List;

import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.JwtTokenService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;
    private final SecurityErrorWriter securityErrorWriter;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserRepository userRepository,
            SecurityErrorWriter securityErrorWriter
    ) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.securityErrorWriter = securityErrorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (token.isEmpty()) {
            securityErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Token khong hop le.");
            return;
        }

        try {
            JwtTokenService.TokenPayload payload = jwtTokenService.parse(token);
            User user = userRepository.findById(payload.userId()).orElse(null);
            if (user == null) {
                securityErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Nguoi dung khong ton tai.");
                return;
            }
            if (user.getTokenVersion() != payload.tokenVersion()) {
                securityErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Phien dang nhap da het hieu luc.");
                return;
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                String message = user.getStatus() == UserStatus.LOCKED
                        ? "Tai khoan da bi khoa."
                        : "Tai khoan da bi vo hieu hoa.";
                securityErrorWriter.write(response, HttpStatus.FORBIDDEN.value(), "FORBIDDEN", message);
                return;
            }

            AuthenticatedUser principal = new AuthenticatedUser(
                    user.getId(),
                    user.getUsername(),
                    user.getRole(),
                    user.getStatus(),
                    user.getTokenVersion()
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    token,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException ex) {
            securityErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Phien dang nhap da het han.");
        } catch (JwtException | IllegalArgumentException ex) {
            securityErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Token khong hop le.");
        }
    }

    /**
     * Ưu tiên token từ header {@code Authorization: Bearer ...}. Trình duyệt không
     * gửi được header cho {@code EventSource}, nên chỉ với endpoint SSE stream mới
     * chấp nhận token qua query param {@code ?token=} (hạn chế lộ token trong log).
     * Trả về {@code null} nếu không có token nào (request ẩn danh).
     */
    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        if (isSseStreamRequest(request)) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null) {
                return tokenParam.trim();
            }
        }
        return null;
    }

    private boolean isSseStreamRequest(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().endsWith("/api/notifications/stream");
    }
}
