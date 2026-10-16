package com.medicalchatbot.backend.support;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.security.AuthenticatedUser;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Builders for users and authenticated principals used across unit tests. */
public final class TestUsers {

    private TestUsers() {
    }

    public static User user(UserRole role) {
        return user(UUID.randomUUID(), role.name().toLowerCase(Locale.ROOT) + "_demo", role);
    }

    public static User user(UUID id, String username, UserRole role) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@medical-chatbot.local")
                .displayName(username)
                .passwordHash("hash")
                .role(role)
                .status(UserStatus.ACTIVE)
                .build();
    }

    /** Puts the user in the security context, as the JWT filter would. */
    public static void signIn(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, "token", List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }

    /** Signs in a USER known only by id, for code that never loads the account. */
    public static void signIn(UUID userId) {
        signIn(user(userId, "user_demo", UserRole.USER));
    }

    /** Signs the user in and lets the mocked repository find them: services load the current user by id. */
    public static void signIn(User user, UserRepository userRepository) {
        signIn(user);
        Mockito.lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    public static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
