package com.medicalchatbot.backend.config;

import java.util.Collections;

import com.medicalchatbot.backend.exception.RateLimitExceededException;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final long windowInSeconds = 60L;
    private final RedisScript<Long> rateLimitScript;

    private final QuotaService quotaService;
    private final CurrentUserService currentUserService;

    public RateLimitInterceptor(
            StringRedisTemplate redisTemplate,
            QuotaService quotaService,
            CurrentUserService currentUserService) {
        this.redisTemplate = redisTemplate;
        this.quotaService = quotaService;
        this.currentUserService = currentUserService;

        String script = "local current = redis.call('INCR', KEYS[1]) " +
                "if current == 1 then " +
                "   redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
                "end " +
                "return current";


        this.rateLimitScript = new DefaultRedisScript<>(script, Long.class);

    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String username = currentUserService.getCurrentUsernameOrNull();
        if (username == null) {
            // Endpoint yêu cầu đăng nhập nên tới đây luôn có user; nếu không có thì
            // để filter auth từ chối, không rate limit theo IP.
            return true;
        }
        int maxLimit = quotaService.getRateLimitForUser(username);

        String key = "rate_limit:user:" + username;

        Long currentRequests = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(windowInSeconds)
        );


        if (currentRequests != null && currentRequests > maxLimit) {
            log.warn("Rate limit exceeded for key {} ({} > {})", key, currentRequests, maxLimit);

            throw new RateLimitExceededException(
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút.",
                    key
            );
        }

        return true;
    }
}
