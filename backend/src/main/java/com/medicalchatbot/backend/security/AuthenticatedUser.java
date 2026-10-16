package com.medicalchatbot.backend.security;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Principal of a request authenticated by {@link JwtAuthenticationFilter}. */
public record AuthenticatedUser(UUID id, String username, UserRole role) implements Principal {

    @Override
    public String getName() {
        return username;
    }

    /** The user of the current request, if it was authenticated with a JWT. */
    public static Optional<AuthenticatedUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
