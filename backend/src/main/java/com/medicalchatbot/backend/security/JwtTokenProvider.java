package com.medicalchatbot.backend.security;

import java.time.Instant;

import com.medicalchatbot.backend.config.JwtConfig;
import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived access tokens. The {@value #TOKEN_VERSION_CLAIM} claim lets the server revoke
 * every outstanding token of a user by bumping {@link User#getTokenVersion()}.
 */
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class JwtTokenProvider {

    public static final String TOKEN_TYPE = "Bearer";
    public static final String USERNAME_CLAIM = "username";
    public static final String TOKEN_VERSION_CLAIM = "token_version";

    JwtEncoder jwtEncoder;
    JwtProperties properties;

    /** Subject is the user id; roles are not in the token, they are reloaded on every request. */
    public String generateToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim(USERNAME_CLAIM, user.getUsername())
                .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.ALGORITHM).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long expiresInSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
