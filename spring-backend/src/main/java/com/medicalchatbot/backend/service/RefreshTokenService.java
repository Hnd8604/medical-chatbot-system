package com.medicalchatbot.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.config.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quản lý refresh token dạng opaque, lưu trong Redis, có rotation.
 *
 * <p>Token gửi cho client có dạng {@code {jti}.{secret}}. Redis chỉ lưu hash SHA-256
 * của phần secret nên kể cả lộ Redis cũng không tái tạo được token. Mỗi key tự hết hạn
 * theo TTL = thời hạn refresh nên không cần job dọn dẹp.</p>
 *
 * <p>Mỗi lần {@link #rotate(String)} sẽ xóa jti cũ và cấp jti mới (rotation). Một refresh
 * token cũ bị dùng lại sau khi đã xoay sẽ không còn trong Redis và bị từ chối.</p>
 */
@Service
public class RefreshTokenService {

    private static final String TOKEN_KEY_PREFIX = "refresh_token:";
    private static final String USER_SET_PREFIX = "refresh_token:user:";
    private static final int SECRET_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final Duration refreshTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.refreshTtl = Duration.ofMinutes(jwtProperties.refreshExpirationMinutes());
    }

    /**
     * Cấp một refresh token mới cho user và lưu vào Redis.
     *
     * @return chuỗi token thô {@code {jti}.{secret}} để trả cho client (chỉ thấy 1 lần).
     */
    public String issue(User user) {
        String jti = UUID.randomUUID().toString();
        String secret = randomSecret();
        String secretHash = sha256Hex(secret);

        String value = user.getId() + "|" + user.getTokenVersion() + "|" + secretHash;
        redisTemplate.opsForValue().set(tokenKey(jti), value, refreshTtl);

        String userSetKey = userSetKey(user.getId());
        redisTemplate.opsForSet().add(userSetKey, jti);
        redisTemplate.expire(userSetKey, refreshTtl);

        return jti + "." + secret;
    }

    /**
     * Xác thực + xoay refresh token: xóa token cũ và trả về chủ sở hữu để cấp token mới.
     *
     * @throws ResponseStatusException 401 nếu token sai định dạng, không tồn tại (đã xoay/hết hạn)
     *                                 hoặc secret không khớp.
     */
    public RotationResult rotate(String rawToken) {
        ParsedToken parsed = parse(rawToken);

        String key = tokenKey(parsed.jti());
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            throw unauthorized("Phien dang nhap da het han hoac khong hop le.");
        }

        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) {
            redisTemplate.delete(key);
            throw unauthorized("Refresh token khong hop le.");
        }

        UUID userId = UUID.fromString(parts[0]);
        int tokenVersion = Integer.parseInt(parts[1]);
        String storedHash = parts[2];

        if (!constantTimeEquals(sha256Hex(parsed.secret()), storedHash)) {
            throw unauthorized("Refresh token khong hop le.");
        }

        // Rotation: token này đã được dùng, vô hiệu hóa ngay.
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(userSetKey(userId), parsed.jti());

        return new RotationResult(userId, tokenVersion);
    }

    /** Thu hồi mọi refresh token đang sống của user (dùng khi logout hoặc phát hiện bất thường). */
    public void revokeAllForUser(UUID userId) {
        String userSetKey = userSetKey(userId);
        Set<String> jtis = redisTemplate.opsForSet().members(userSetKey);
        if (jtis != null) {
            for (String jti : jtis) {
                redisTemplate.delete(tokenKey(jti));
            }
        }
        redisTemplate.delete(userSetKey);
    }

    private ParsedToken parse(String rawToken) {
        if (rawToken == null) {
            throw unauthorized("Refresh token khong hop le.");
        }
        int dot = rawToken.indexOf('.');
        if (dot <= 0 || dot == rawToken.length() - 1) {
            throw unauthorized("Refresh token khong hop le.");
        }
        return new ParsedToken(rawToken.substring(0, dot), rawToken.substring(dot + 1));
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    private static String tokenKey(String jti) {
        return TOKEN_KEY_PREFIX + jti;
    }

    private static String userSetKey(UUID userId) {
        return USER_SET_PREFIX + userId;
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    public record RotationResult(UUID userId, int tokenVersion) {
    }

    private record ParsedToken(String jti, String secret) {
    }
}
