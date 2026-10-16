package com.medicalchatbot.backend.security;

import java.time.Duration;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-client-IP rate limit on the public auth actions (login, registration, password reset), which
 * anyone can call and which would otherwise allow unlimited password guessing and email flooding.
 * Each endpoint has its own window, so a burst of logins does not block a password reset.
 *
 * <p>The client is {@link HttpServletRequest#getRemoteAddr()}: behind a reverse proxy, enable
 * {@code server.forward-headers-strategy} so that it is the real client and not the proxy.</p>
 */
@Slf4j
@Component
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String KEY_PREFIX = "rate_limit:auth:";

    private final FixedWindowCounter counter;
    private final int limitPerMinute;

    public AuthRateLimitInterceptor(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.counter = new FixedWindowCounter(redisTemplate);
        this.limitPerMinute = appProperties.rateLimit().authRequestsPerMinute();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String client = request.getRemoteAddr();
        String endpoint = request.getRequestURI();
        if (!counter.tryAcquire(KEY_PREFIX + endpoint + ":" + client, limitPerMinute, WINDOW)) {
            log.warn("Auth rate limit exceeded for {} on {} (more than {} per minute)", client, endpoint, limitPerMinute);
            throw new RateLimitExceededException("IP " + client + " (" + endpoint + ")");
        }
        return true;
    }
}
