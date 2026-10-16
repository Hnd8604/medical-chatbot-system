package com.medicalchatbot.backend.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.util.SecureTokens.OpaqueToken;
import com.medicalchatbot.backend.util.SecureTokens;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Codes and tickets of the forgot-password flow, stored in Redis with the same guarantees as
 * {@link RefreshTokenService}: only hashes are kept, keys expire, and every check that consumes
 * something is a single atomic Redis operation, so parallel requests cannot share one read.
 *
 * <p>Flow: {@link #issueCode} emails a 6-digit code → {@link #verifyCode} trades a correct code
 * for a one-time ticket → {@link #consumeTicket} spends the ticket when the new password is set.</p>
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PasswordResetOtpService {

    private static final String OTP_KEY_PREFIX = "password_reset:otp:";
    private static final String COOLDOWN_KEY_PREFIX = "password_reset:cooldown:";
    private static final String TICKET_KEY_PREFIX = "password_reset:ticket:";
    private static final int CODE_LENGTH = 6;
    private static final String SEPARATOR = "|";
    private static final long MATCHED = 1L;

    /**
     * Checks a code against the stored {@code attempts|hash} and counts the attempt in one step.
     * A match deletes the code; so does the wrong attempt that reaches the limit; any other wrong
     * attempt is counted without touching the expiry. Returns 1 on a match, 0 otherwise.
     *
     * <p>Comparing in Redis is not constant-time, which is harmless here: a code allows only a few
     * attempts in total, far too few to learn anything from timing.</p>
     */
    private static final RedisScript<Long> VERIFY_CODE = new DefaultRedisScript<>("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then
                return 0
            end
            local separator = string.find(stored, '|', 1, true)
            if not separator then
                redis.call('DEL', KEYS[1])
                return 0
            end
            local codeHash = string.sub(stored, separator + 1)
            if codeHash == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            local attempts = (tonumber(string.sub(stored, 1, separator - 1)) or 0) + 1
            if attempts >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1])
            else
                redis.call('SET', KEYS[1], attempts .. '|' .. codeHash, 'KEEPTTL')
            end
            return 0
            """, Long.class);

    StringRedisTemplate redisTemplate;
    PasswordResetProperties properties;

    /**
     * Creates a new code for the email, replacing any previous one and resetting the attempt count.
     *
     * @return the raw code to email, or empty while the resend cooldown is running
     */
    public Optional<String> issueCode(String email) {
        String normalizedEmail = TextUtils.normalizeEmail(email);
        Duration cooldown = properties.resendCooldown();
        // Check and start the cooldown in one step, so two parallel requests cannot both pass it.
        if (!cooldown.isZero() && !Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(COOLDOWN_KEY_PREFIX + normalizedEmail, "1", cooldown))) {
            return Optional.empty();
        }

        String code = SecureTokens.randomDigits(CODE_LENGTH);
        redisTemplate.opsForValue().set(
                otpKey(normalizedEmail), 0 + SEPARATOR + SecureTokens.sha256Hex(code), properties.otpTtl());
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
        Long result = redisTemplate.execute(
                VERIFY_CODE,
                List.of(otpKey(normalizedEmail)),
                SecureTokens.sha256Hex(TextUtils.strip(code)),
                Integer.toString(properties.maxAttempts())
        );
        if (result == null || result != MATCHED) {
            throw invalidCode();
        }
        return issueTicket(normalizedEmail);
    }

    /**
     * Validates and spends a reset ticket. It is read and deleted in one step, so of two parallel
     * requests presenting it only one gets it.
     *
     * @return the email the ticket was issued for
     * @throws AppException when the ticket is invalid, expired or already used
     */
    public String consumeTicket(String rawTicket) {
        OpaqueToken ticket = OpaqueToken.parse(rawTicket).orElseThrow(PasswordResetOtpService::invalidTicket);
        String stored = redisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket.id());
        if (stored == null) {
            throw invalidTicket();
        }
        String[] parts = stored.split("\\|", 2);
        if (parts.length != 2 || !ticket.matches(parts[0])) {
            throw invalidTicket();
        }
        return parts[1];
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
        return new AppException(ErrorCode.RESET_CODE_INVALID);
    }

    private static AppException invalidTicket() {
        return new AppException(ErrorCode.RESET_TICKET_INVALID);
    }
}
