package com.medicalchatbot.backend.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.medicalchatbot.backend.exception.RateLimitExceededException;
import com.medicalchatbot.backend.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitInterceptorTest {

    private static final String LOGIN_KEY = "rate_limit:auth:/api/v1/auth/login:203.0.113.7";

    @Mock
    private StringRedisTemplate redisTemplate;

    private AuthRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // TestProperties allows 10 requests per minute.
        interceptor = new AuthRateLimitInterceptor(redisTemplate, TestProperties.app());
    }

    @Test
    void requestsWithinTheLimitPass() {
        requestsCountedSoFar(10L);

        assertTrue(interceptor.preHandle(login(), new MockHttpServletResponse(), new Object()));
        // One window per endpoint and client IP, expiring after a minute.
        verify(redisTemplate).execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(LOGIN_KEY)), eq("60"));
    }

    @Test
    void requestOverTheLimitIsRejected() {
        requestsCountedSoFar(11L);

        assertThrows(RateLimitExceededException.class,
                () -> interceptor.preHandle(login(), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void onlyPostsAreCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(redisTemplate);
    }

    private void requestsCountedSoFar(long count) {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(LOGIN_KEY)), eq("60")))
                .thenReturn(count);
    }

    private static MockHttpServletRequest login() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }
}
