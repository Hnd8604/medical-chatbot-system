package com.medicalchatbot.backend.security;

import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a verified access token into the request's {@link AuthenticatedUser}. The user is reloaded
 * on every request, so a lock, a role change, a logout or a password change (token version) takes
 * effect immediately instead of when the token expires.
 *
 * <p>Deliberately not a Spring bean: MVC would also register it as a type converter.</p>
 */
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    UserRepository userRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = userRepository.findById(userId(jwt))
                .orElseThrow(() -> new TokenAuthenticationException(ErrorCode.ACCOUNT_NOT_EXISTED));
        Number tokenVersion = jwt.getClaim(JwtTokenProvider.TOKEN_VERSION_CLAIM);
        if (tokenVersion == null || tokenVersion.intValue() != user.getTokenVersion()) {
            throw new TokenAuthenticationException(ErrorCode.TOKEN_REVOKED);
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new TokenAuthenticationException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new TokenAuthenticationException(ErrorCode.ACCOUNT_DISABLED);
        }

        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new TokenAuthenticationException(ErrorCode.TOKEN_INVALID);
        }
    }
}
