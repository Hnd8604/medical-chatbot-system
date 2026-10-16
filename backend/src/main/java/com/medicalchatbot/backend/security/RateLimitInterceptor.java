package com.medicalchatbot.backend.security;

import java.time.Duration;
import java.util.Optional;

import com.medicalchatbot.backend.exception.RateLimitExceededException;
import com.medicalchatbot.backend.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-user fixed-window rate limit on sending chat messages ({@code POST /chat}). The limit
 * per minute comes from the user's quota policy.
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String KEY_PREFIX = "rate_limit:user:";

    private final FixedWindowCounter counter;
    private final QuotaService quotaService;

    public RateLimitInterceptor(StringRedisTemplate redisTemplate, QuotaService quotaService) {
        this.counter = new FixedWindowCounter(redisTemplate);
        this.quotaService = quotaService;
    }

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
        if (!counter.tryAcquire(key, limit, WINDOW)) {
            log.warn("Rate limit exceeded for {} (more than {} per minute)", key, limit);
            throw new RateLimitExceededException(key);
        }
        return true;
    }
}
