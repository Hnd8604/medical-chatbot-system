package com.medicalchatbot.backend.security;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Fixed-window request counter in Redis, shared by the rate-limit interceptors. */
final class FixedWindowCounter {

    /** Atomically counts the request and starts the window on the first one. */
    private static final RedisScript<Long> INCREMENT_IN_WINDOW = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    FixedWindowCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Counts one request under {@code key}; false once the current window holds more than {@code limit}. */
    boolean tryAcquire(String key, int limit, Duration window) {
        Long requestsInWindow = redisTemplate.execute(INCREMENT_IN_WINDOW, List.of(key), Long.toString(window.toSeconds()));
        return requestsInWindow == null || requestsInWindow <= limit;
    }
}
