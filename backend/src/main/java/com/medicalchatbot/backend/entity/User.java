package com.medicalchatbot.backend.entity;

import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    String username;

    @Column(unique = true, length = 255)
    String email;

    @Column(name = "display_name", nullable = false, length = 255)
    String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    String passwordHash;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    UserRole role = UserRole.USER;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    UserStatus status = UserStatus.ACTIVE;

    /** Bumped on logout/password change so every JWT issued before becomes invalid. */
    @Column(name = "token_version", nullable = false)
    int tokenVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quota_policy_id")
    QuotaPolicy quotaPolicy;

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public void incrementTokenVersion() {
        this.tokenVersion += 1;
    }
}
