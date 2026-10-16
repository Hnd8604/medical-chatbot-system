package com.medicalchatbot.backend.service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.util.SecureTokens;
import com.medicalchatbot.backend.util.SecureTokens.OpaqueToken;
import com.medicalchatbot.backend.util.TextUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Codes and tickets of the forgot-password flow, stored in Redis with the same guarantees as
 * {@link RefreshTokenService}: only hashes are kept, comparisons are constant-time, keys expire.
 *
 * <p>Flow: {@link #issueCode} emails a 6-digit code → {@link #verifyCode} trades a correct code
 * for a one-time ticket → {@link #consumeTicket} spends the ticket when the new password is set.</p>
 */
@Service
public class PasswordResetOtpService {

    private static final String OTP_KEY_PREFIX = "password_reset:otp:";
    private static final String COOLDOWN_KEY_PREFIX = "password_reset:cooldown:";
    private static final String TICKET_KEY_PREFIX = "password_reset:ticket:";
    private static final int CODE_LENGTH = 6;
    private static final String SEPARATOR = "|";

    private final StringRedisTemplate redisTemplate;
    private final PasswordResetProperties properties;

    public PasswordResetOtpService(StringRedisTemplate redisTemplate, PasswordResetProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Creates a new code for the email, replacing any previous one and resetting the attempt count.
     *
     * @return the raw code to email, or empty while the resend cooldown is running
     */
    public Optional<String> issueCode(String email) {
        String normalizedEmail = TextUtils.normalizeEmail(email);
        Duration cooldown = properties.resendCooldown();
        String cooldownKey = COOLDOWN_KEY_PREFIX + normalizedEmail;
        if (!cooldown.isZero() && Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            return Optional.empty();
        }

        String code = SecureTokens.randomDigits(CODE_LENGTH);
        storeCode(otpKey(normalizedEmail), 0, SecureTokens.sha256Hex(code), properties.otpTtl());
        if (!cooldown.isZero()) {
            redisTemplate.opsForValue().set(cooldownKey, "1", cooldown);
        }
        return Optional.of(code);
    }

    /**
     * Checks a code. Too many wrong attempts invalidate it; a correct code is consumed.
     *
     * @return the raw reset ticket for the final step
     * @throws AppException when the code is wrong or has expired
     */
    public String verifyCode(String email, String code) {
        String normalizedEmail = TextUtils.normalizeEmail(email);
        String key = otpKey(normalizedEmail);
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw invalidCode();
        }
        String[] parts = stored.split("\\|", 2);
        if (parts.length != 2) {
            redisTemplate.delete(key);
            throw invalidCode();
        }

        String codeHash = parts[1];
        if (!SecureTokens.constantTimeEquals(SecureTokens.sha256Hex(TextUtils.strip(code)), codeHash)) {
            recordFailedAttempt(key, Integer.parseInt(parts[0]) + 1, codeHash);
            throw invalidCode();
        }

        redisTemplate.delete(key);
        return issueTicket(normalizedEmail);
    }

    /**
     * Validates and spends a reset ticket.
     *
     * @return the email the ticket was issued for
     * @throws AppException when the ticket is invalid, expired or already used
     */
    public String consumeTicket(String rawTicket) {
        OpaqueToken ticket = OpaqueToken.parse(rawTicket).orElseThrow(PasswordResetOtpService::invalidTicket);
        String key = TICKET_KEY_PREFIX + ticket.id();
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw invalidTicket();
        }
        String[] parts = stored.split("\\|", 2);
        if (parts.length != 2 || !ticket.matches(parts[0])) {
            throw invalidTicket();
        }
        redisTemplate.delete(key);
        return parts[1];
    }

    private void recordFailedAttempt(String key, int attempts, String codeHash) {
        if (attempts >= properties.maxAttempts()) {
            redisTemplate.delete(key);
            return;
        }
        // Keep the original expiry: a wrong attempt must not extend the life of the code.
        Long remainingSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        Duration ttl = remainingSeconds != null && remainingSeconds > 0
                ? Duration.ofSeconds(remainingSeconds)
                : properties.otpTtl();
        storeCode(key, attempts, codeHash, ttl);
    }

    private void storeCode(String key, int attempts, String codeHash, Duration ttl) {
        redisTemplate.opsForValue().set(key, attempts + SEPARATOR + codeHash, ttl);
    }

    private String issueTicket(String normalizedEmail) {
        OpaqueToken ticket = OpaqueToken.generate();
        redisTemplate.opsForValue().set(
                TICKET_KEY_PREFIX + ticket.id(),
                ticket.secretHash() + SEPARATOR + normalizedEmail,
                properties.ticketTtl()
        );
        return ticket.value();
    }

    private static String otpKey(String normalizedEmail) {
        return OTP_KEY_PREFIX + normalizedEmail;
    }

    private static AppException invalidCode() {
        return AppException.invalid("Mã xác thực không đúng hoặc đã hết hạn.");
    }

    private static AppException invalidTicket() {
        return AppException.invalid("Yêu cầu đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.");
    }
}
