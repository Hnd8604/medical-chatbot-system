package com.medicalchatbot.backend.service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.util.SecureTokens.OpaqueToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Opaque, rotating refresh tokens stored in Redis.
 *
 * <p>Clients receive {@code {jti}.{secret}}; Redis keeps only the SHA-256 of the secret and every
 * key expires with the token, so no cleanup job is needed. {@link #rotate} deletes the presented
 * token, so a replayed token is rejected.</p>
 */
@Service
public class RefreshTokenService {

    private static final String TOKEN_KEY_PREFIX = "refresh_token:";
    private static final String USER_SET_PREFIX = "refresh_token:user:";
    private static final String SEPARATOR = "|";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RefreshTokenService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.ttl = jwtProperties.refreshTokenTtl();
    }

    /** Stores a new refresh token for the user and returns its raw value (shown to the client once). */
    public String issue(User user) {
        OpaqueToken token = OpaqueToken.generate();
        String value = String.join(SEPARATOR, user.getId().toString(), Integer.toString(user.getTokenVersion()), token.secretHash());
        redisTemplate.opsForValue().set(tokenKey(token.id()), value, ttl);

        String userSetKey = userSetKey(user.getId());
        redisTemplate.opsForSet().add(userSetKey, token.id());
        redisTemplate.expire(userSetKey, ttl);
        return token.value();
    }

    /**
     * Validates and consumes a refresh token.
     *
     * @return the owner of the token, for issuing the next pair
     * @throws AppException when the token is malformed, unknown, expired or already used
     */
    public TokenOwner rotate(String rawToken) {
        OpaqueToken token = OpaqueToken.parse(rawToken).orElseThrow(RefreshTokenService::invalidToken);
        String key = tokenKey(token.id());
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw AppException.unauthorized("Phiên đăng nhập đã hết hạn hoặc không hợp lệ.");
        }

        String[] parts = stored.split("\\|", 3);
        if (parts.length != 3) {
            redisTemplate.delete(key);
            throw invalidToken();
        }
        if (!token.matches(parts[2])) {
            throw invalidToken();
        }

        UUID userId = UUID.fromString(parts[0]);
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(userSetKey(userId), token.id());
        return new TokenOwner(userId, Integer.parseInt(parts[1]));
    }

    /** Revokes every live refresh token of the user (logout, password change, suspicious use). */
    public void revokeAll(UUID userId) {
        String userSetKey = userSetKey(userId);
        Set<String> tokenIds = redisTemplate.opsForSet().members(userSetKey);
        if (tokenIds != null) {
            tokenIds.forEach(tokenId -> redisTemplate.delete(tokenKey(tokenId)));
        }
        redisTemplate.delete(userSetKey);
    }

    private static String tokenKey(String tokenId) {
        return TOKEN_KEY_PREFIX + tokenId;
    }

    private static String userSetKey(UUID userId) {
        return USER_SET_PREFIX + userId;
    }

    private static AppException invalidToken() {
        return AppException.unauthorized("Refresh token không hợp lệ.");
    }

    public record TokenOwner(UUID userId, int tokenVersion) {
    }
}
