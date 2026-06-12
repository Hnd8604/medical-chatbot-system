package com.medicalchatbot.backend.config;

import java.util.Collections;

import com.medicalchatbot.backend.exception.RateLimitExceededException;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

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

        String username = currentUserService.getCurrentUsername();
        int maxLimit = quotaService.getRateLimitForUser(username);

        String key = "anonymousUser".equals(username) ?
                "rate_limit:ip:" + getClientIp(request) :
                "rate_limit:user:" + username;

        Long currentRequests = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(windowInSeconds)
        );


        if (currentRequests != null && currentRequests > maxLimit) {
            log.warn("Rate limit exceeded for key {} ({} > {})", key, currentRequests, maxLimit);
            // Ném exception thẳng ra ngoài, không tự ghi chuỗi JSON nữa
            throw new RateLimitExceededException("Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút.");
        }

        return true;
    }


    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // Lấy IP trực tiếp nếu không qua Proxy
            ip = request.getRemoteAddr();
        } else {
            // Nếu có chuỗi Proxy lồng nhau, IP thật luôn nằm ở vị trí đầu tiên
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}