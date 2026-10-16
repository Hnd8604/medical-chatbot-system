package com.medicalchatbot.backend.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies short-lived access tokens. The {@code token_version} claim lets the server
 * revoke every outstanding token of a user by bumping {@link User#getTokenVersion()}.
 */
@Component
public class JwtTokenProvider {

    public static final String TOKEN_TYPE = "Bearer";

    private static final String USERNAME_CLAIM = "username";
    private static final String TOKEN_VERSION_CLAIM = "token_version";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(USERNAME_CLAIM, user.getUsername())
                .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(signingKey)
                .compact();
    }

    /**
     * @throws io.jsonwebtoken.ExpiredJwtException when the token has expired
     * @throws JwtException when the token is malformed or its signature is invalid
     */
    public TokenClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Integer tokenVersion = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
        if (claims.getSubject() == null || tokenVersion == null) {
            throw new JwtException("Missing required claims");
        }
        return new TokenClaims(UUID.fromString(claims.getSubject()), tokenVersion);
    }

    public long expiresInSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    public record TokenClaims(UUID userId, int tokenVersion) {
    }
}
