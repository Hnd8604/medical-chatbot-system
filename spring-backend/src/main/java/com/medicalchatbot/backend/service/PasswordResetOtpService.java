package com.medicalchatbot.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.medicalchatbot.backend.config.PasswordResetProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quản lý mã OTP quên mật khẩu và ticket đặt lại, lưu trong Redis.
 *
 * <p>Theo cùng nguyên tắc bảo mật của {@link RefreshTokenService}: Redis chỉ lưu hash SHA-256,
 * so sánh constant-time, mỗi key tự hết hạn theo TTL nên không cần job dọn dẹp.</p>
 *
 * <p>Luồng: {@link #issueCode(String)} gửi mã 6 số → {@link #verifyCode(String, String)} đổi mã
 * đúng lấy ticket opaque {@code {jti}.{secret}} → {@link #consumeTicket(String)} dùng một lần khi
 * đặt lại mật khẩu.</p>
 */
@Service
public class PasswordResetOtpService {

    private static final String OTP_KEY_PREFIX = "password_reset:otp:";
    private static final String COOLDOWN_KEY_PREFIX = "password_reset:cooldown:";
    private static final String TICKET_KEY_PREFIX = "password_reset:ticket:";
    private static final int SECRET_BYTES = 32;
    private static final int OTP_BOUND = 1_000_000;

    private final StringRedisTemplate redisTemplate;
    private final Duration otpTtl;
    private final Duration ticketTtl;
    private final Duration resendCooldown;
    private final int maxAttempts;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetOtpService(StringRedisTemplate redisTemplate, PasswordResetProperties properties) {
        this.redisTemplate = redisTemplate;
        this.otpTtl = Duration.ofMinutes(properties.otpTtlMinutes());
        this.ticketTtl = Duration.ofMinutes(properties.ticketTtlMinutes());
        this.resendCooldown = Duration.ofSeconds(properties.resendCooldownSeconds());
        this.maxAttempts = properties.maxAttempts();
    }

    /**
     * Sinh và lưu mã OTP 6 số cho email (ghi đè mã cũ, reset bộ đếm sai).
     *
     * @return mã thô để gửi/log; {@link Optional#empty()} nếu đang trong thời gian chờ gửi lại
     *         (chống spam) — caller vẫn trả thông điệp chung để không lộ email tồn tại.
     */
    public Optional<String> issueCode(String email) {
        String normalized = normalize(email);
        String cooldownKey = COOLDOWN_KEY_PREFIX + normalized;
        if (!resendCooldown.isZero() && Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            return Optional.empty();
        }

        String code = randomNumericCode();
        redisTemplate.opsForValue().set(otpKey(normalized), "0|" + sha256Hex(code), otpTtl);
        if (!resendCooldown.isZero()) {
            redisTemplate.opsForValue().set(cooldownKey, "1", resendCooldown);
        }
        return Optional.of(code);
    }

    /**
     * Xác thực mã OTP. Sai quá {@code maxAttempts} lần thì vô hiệu mã. Đúng thì xóa mã và cấp ticket.
     *
     * @return ticket thô {@code {jti}.{secret}} dùng cho bước đặt lại mật khẩu.
     * @throws ResponseStatusException 400 nếu mã sai hoặc đã hết hạn.
     */
    public String verifyCode(String email, String code) {
        String normalized = normalize(email);
        String key = otpKey(normalized);
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw invalidCode();
        }

        String[] parts = stored.split("\\|", 2);
        if (parts.length != 2) {
            redisTemplate.delete(key);
            throw invalidCode();
        }
        int attempts = Integer.parseInt(parts[0]);
        String storedHash = parts[1];

        if (!constantTimeEquals(sha256Hex(safe(code)), storedHash)) {
            attempts++;
            if (attempts >= maxAttempts) {
                redisTemplate.delete(key);
            } else {
                Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                Duration ttl = remaining != null && remaining > 0 ? Duration.ofSeconds(remaining) : otpTtl;
                redisTemplate.opsForValue().set(key, attempts + "|" + storedHash, ttl);
            }
            throw invalidCode();
        }

        redisTemplate.delete(key);
        return issueTicket(normalized);
    }

    /**
     * Xác thực + tiêu thụ ticket (dùng một lần).
     *
     * @return email chủ ticket.
     * @throws ResponseStatusException 400 nếu ticket sai hoặc đã hết hạn/đã dùng.
     */
    public String consumeTicket(String ticket) {
        if (ticket == null) {
            throw invalidTicket();
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            throw invalidTicket();
        }
        String jti = ticket.substring(0, dot);
        String secret = ticket.substring(dot + 1);

        String key = TICKET_KEY_PREFIX + jti;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw invalidTicket();
        }
        String[] parts = stored.split("\\|", 2);
        if (parts.length != 2 || !constantTimeEquals(sha256Hex(secret), parts[0])) {
            throw invalidTicket();
        }
        redisTemplate.delete(key);
        return parts[1];
    }

    private String issueTicket(String normalizedEmail) {
        String jti = UUID.randomUUID().toString();
        String secret = randomSecret();
        redisTemplate.opsForValue().set(
                TICKET_KEY_PREFIX + jti,
                sha256Hex(secret) + "|" + normalizedEmail,
                ticketTtl
        );
        return jti + "." + secret;
    }

    private String randomNumericCode() {
        return String.format("%06d", secureRandom.nextInt(OTP_BOUND));
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String otpKey(String normalizedEmail) {
        return OTP_KEY_PREFIX + normalizedEmail;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Khong the bam SHA-256", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ma xac thuc khong dung hoac da het han.");
    }

    private static ResponseStatusException invalidTicket() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yeu cau dat lai mat khau khong hop le hoac da het han.");
    }
}
