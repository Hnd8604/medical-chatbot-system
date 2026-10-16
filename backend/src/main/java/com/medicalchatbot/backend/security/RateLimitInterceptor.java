package com.medicalchatbot.backend.security;

import java.util.List;
import java.util.Optional;

import com.medicalchatbot.backend.exception.RateLimitExceededException;
import com.medicalchatbot.backend.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-user fixed-window rate limit on sending chat messages ({@code POST /api/chat}). The limit
 * per minute comes from the user's quota policy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String WINDOW_SECONDS = "60";
    private static final String KEY_PREFIX = "rate_limit:user:";

    /** Atomically counts the request and starts the window on the first one. */
    private static final RedisScript<Long> INCREMENT_IN_WINDOW = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final QuotaService quotaService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // The endpoint requires authentication, so an anonymous request is rejected by security instead.
        Optional<AuthenticatedUser> user = AuthenticatedUser.current();
        if (user.isEmpty()) {
            return true;
        }

        String key = KEY_PREFIX + user.get().username();
        int limit = quotaService.rateLimitPerMinute(user.get().id());
        Long requestsInWindow = redisTemplate.execute(INCREMENT_IN_WINDOW, List.of(key), WINDOW_SECONDS);
        if (requestsInWindow != null && requestsInWindow > limit) {
            log.warn("Rate limit exceeded for {} ({} > {})", key, requestsInWindow, limit);
            throw new RateLimitExceededException(key);
        }
        return true;
    }
}
