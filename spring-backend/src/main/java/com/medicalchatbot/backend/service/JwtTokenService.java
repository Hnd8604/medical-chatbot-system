package com.medicalchatbot.backend.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.medicalchatbot.backend.config.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresInSeconds());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("token_version", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public TokenPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new TokenPayload(
                UUID.fromString(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("token_version", Integer.class)
        );
    }

    public long expiresInSeconds() {
        return properties.expirationMinutes() * 60L;
    }

    public record TokenPayload(UUID userId, String username, int tokenVersion) {
    }
}
