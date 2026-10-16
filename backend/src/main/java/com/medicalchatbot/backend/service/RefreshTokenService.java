package com.medicalchatbot.backend.service;

import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.util.SecureTokens.OpaqueToken;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Opaque, rotating refresh tokens stored in Redis.
 *
 * <p>Clients receive {@code {jti}.{secret}}; Redis keeps only the SHA-256 of the secret and every
 * key expires with the token, so no cleanup job is needed. {@link #rotate} reads and deletes the
 * presented token in one step: a replayed token is rejected, and of two parallel refreshes with the
 * same token only one succeeds.</p>
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RefreshTokenService {

    private static final String TOKEN_KEY_PREFIX = "refresh_token:";
    private static final String USER_SET_PREFIX = "refresh_token:user:";
    private static final String SEPARATOR = "|";

    StringRedisTemplate redisTemplate;
    JwtProperties jwtProperties;

    /** Stores a new refresh token for the user and returns its raw value (shown to the client once). */
    public String issue(User user) {
        OpaqueToken token = OpaqueToken.generate();
        String value = String.join(SEPARATOR, user.getId().toString(), Integer.toString(user.getTokenVersion()), token.secretHash());
        redisTemplate.opsForValue().set(tokenKey(token.id()), value, jwtProperties.refreshTokenTtl());

        String userSetKey = userSetKey(user.getId());
        redisTemplate.opsForSet().add(userSetKey, token.id());
        redisTemplate.expire(userSetKey, jwtProperties.refreshTokenTtl());
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
        String stored = redisTemplate.opsForValue().getAndDelete(tokenKey(token.id()));
        if (stored == null) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        String[] parts = stored.split("\\|", 3);
        if (parts.length != 3) {
            throw invalidToken();
        }
        UUID userId = UUID.fromString(parts[0]);
        redisTemplate.opsForSet().remove(userSetKey(userId), token.id());
        if (!token.matches(parts[2])) {
            throw invalidToken();
        }
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
        return new AppException(ErrorCode.REFRESH_TOKEN_INVALID);
    }

    public record TokenOwner(UUID userId, int tokenVersion) {
    }
}
