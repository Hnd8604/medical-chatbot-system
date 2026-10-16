package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The check-and-consume steps of OTP codes, reset tickets and refresh tokens against a real Redis:
 * their atomicity lives in Redis itself (Lua script, GETDEL), which a mock cannot show.
 *
 * <p>Skipped unless {@code TEST_REDIS_HOST} is set, e.g. with a throwaway container
 * {@code docker run --rm -p 127.0.0.1:56379:6379 redis:7.2-alpine} and
 * {@code TEST_REDIS_HOST=localhost TEST_REDIS_PORT=56379}.</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_REDIS_HOST", matches = ".+")
class RedisAtomicityTest {

    private static final int PARALLEL_REQUESTS = 20;
    private static final int MAX_ATTEMPTS = 5;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final String email = "atomicity-" + UUID.randomUUID() + "@test.local";
    private PasswordResetOtpService otpService;
    private RefreshTokenService refreshTokenService;

    @BeforeAll
    static void connect() {
        int port = Integer.parseInt(Optional.ofNullable(System.getenv("TEST_REDIS_PORT")).orElse("6379"));
        connectionFactory = new LettuceConnectionFactory(System.getenv("TEST_REDIS_HOST"), port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        // No resend cooldown, so each test can issue its code immediately.
        otpService = new PasswordResetOtpService(redisTemplate,
                new PasswordResetProperties(10, 10, MAX_ATTEMPTS, 0, "no-reply@test.local", "Test"));
        refreshTokenService = new RefreshTokenService(redisTemplate, new JwtProperties("x".repeat(32), 30, 60));
    }

    @Test
    void parallelWrongGuessesGetNoMoreThanTheAllowedAttempts() throws Exception {
        String code = otpService.issueCode(email).orElseThrow();

        runInParallel(() -> otpService.verifyCode(email, wrongCode(code)));

        // Far more guesses than allowed arrived at once: the code must be gone, so even the right one fails.
        assertThrows(AppException.class, () -> otpService.verifyCode(email, code));
    }

    @Test
    void theLastAllowedAttemptMayStillBeTheRightCode() {
        String code = otpService.issueCode(email).orElseThrow();
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThrows(AppException.class, () -> otpService.verifyCode(email, wrongCode(code)));
        }

        Long secondsLeft = redisTemplate.getExpire("password_reset:otp:" + email);
        assertTrue(secondsLeft != null && secondsLeft > 0 && secondsLeft <= 600,
                "wrong attempts must keep the original expiry");
        assertNotNull(otpService.verifyCode(email, code));
    }

    @Test
    void aResetTicketCanBeSpentOnlyOnce() throws Exception {
        String code = otpService.issueCode(email).orElseThrow();
        String ticket = otpService.verifyCode(email, code);

        List<Boolean> outcomes = runInParallel(() -> otpService.consumeTicket(ticket));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void parallelRefreshesWithTheSameTokenSucceedOnlyOnce() throws Exception {
        User user = TestUsers.user(UserRole.USER);
        String token = refreshTokenService.issue(user);
        try {
            List<Boolean> outcomes = runInParallel(() -> refreshTokenService.rotate(token));

            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        } finally {
            refreshTokenService.revokeAll(user.getId());
        }
    }

    private static String wrongCode(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    /** Releases all calls at the same moment; returns, per call, whether it was accepted. */
    private static List<Boolean> runInParallel(Callable<?> call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        call.call();
                        return true;
                    } catch (AppException rejected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> result : results) {
                outcomes.add(result.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}
