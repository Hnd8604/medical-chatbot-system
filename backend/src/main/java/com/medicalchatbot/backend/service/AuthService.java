package com.medicalchatbot.backend.service;

import java.util.Locale;

import com.medicalchatbot.backend.dto.request.auth.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.LoginRequest;
import com.medicalchatbot.backend.dto.request.auth.RefreshTokenRequest;
import com.medicalchatbot.backend.dto.request.auth.RegisterRequest;
import com.medicalchatbot.backend.dto.request.auth.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.response.auth.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.auth.LoginResponse;
import com.medicalchatbot.backend.dto.response.auth.TokenResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.security.JwtTokenProvider;
import com.medicalchatbot.backend.util.PasswordPolicy;
import com.medicalchatbot.backend.util.SecurityUtils;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, token refresh, registration and management of the signed-in account. The shape of the
 * input (lengths, username and email format) is checked by the request DTOs.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthService {

    UserRepository userRepository;
    QuotaPolicyRepository quotaPolicyRepository;
    PasswordEncoder passwordEncoder;
    JwtTokenProvider jwtTokenProvider;
    RefreshTokenService refreshTokenService;
    PatientLinkService patientLinkService;
    UserMapper userMapper;
    AuthAuditService authAudit;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String credential = request.usernameOrEmail().strip();
        User user = userRepository.findByUsernameOrEmailIgnoreCase(credential)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> {
                    authAudit.loginFailed(null, credential, "INVALID_CREDENTIALS");
                    return new AppException(ErrorCode.INVALID_CREDENTIALS);
                });
        if (!user.isActive()) {
            authAudit.loginFailed(user, credential, user.getStatus().name());
            throw inactiveAccount(user);
        }

        authAudit.loginSucceeded(user);
        return new LoginResponse(
                jwtTokenProvider.generateToken(user),
                refreshTokenService.issue(user),
                JwtTokenProvider.TOKEN_TYPE,
                jwtTokenProvider.expiresInSeconds(),
                toAuthUserResponse(user)
        );
    }

    /** Rotates the refresh token: the presented token is revoked and a new pair is issued. */
    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.TokenOwner owner = refreshTokenService.rotate(request.refreshToken());
        User user = userRepository.findById(owner.userId())
                .orElseThrow(() -> {
                    refreshTokenService.revokeAll(owner.userId());
                    return new AppException(ErrorCode.ACCOUNT_NOT_EXISTED);
                });
        if (!user.isActive()) {
            throw inactiveAccount(user);
        }
        // A changed token version (logout, password change) invalidates every older session.
        if (user.getTokenVersion() != owner.tokenVersion()) {
            refreshTokenService.revokeAll(user.getId());
            throw new AppException(ErrorCode.TOKEN_REVOKED);
        }

        authAudit.tokenRefreshed(user);
        return new TokenResponse(
                jwtTokenProvider.generateToken(user),
                refreshTokenService.issue(user),
                JwtTokenProvider.TOKEN_TYPE,
                jwtTokenProvider.expiresInSeconds()
        );
    }

    /** Self-registration always creates an active USER on the default quota policy. */
    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String username = TextUtils.strip(request.username()).toLowerCase(Locale.ROOT);
        String email = TextUtils.normalizeEmail(request.email());
        PasswordPolicy.validate(request.password(), request.passwordConfirmation());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new AppException(ErrorCode.USERNAME_EXISTED);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        }

        QuotaPolicy quotaPolicy = quotaPolicyRepository.findByName(QuotaPolicy.DEFAULT_USER_POLICY)
                .orElseThrow(() -> new AppException(ErrorCode.QUOTA_POLICY_NOT_CONFIGURED));
        User user = User.builder()
                .username(username)
                .email(email)
                .displayName(TextUtils.strip(request.displayName()))
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .quotaPolicy(quotaPolicy)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // The unique constraints close the race between the checks above and the insert.
            throw new AppException(ErrorCode.USERNAME_OR_EMAIL_EXISTED, exception);
        }

        authAudit.registered(user);
        return toAuthUserResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser() {
        return toAuthUserResponse(requireCurrentUser());
    }

    @Transactional
    public AuthUserResponse updateProfile(UpdateProfileRequest request) {
        User user = requireCurrentUser();
        String email = TextUtils.normalizeEmail(request.email());

        boolean emailChanged = !email.equalsIgnoreCase(TextUtils.strip(user.getEmail()));
        if (emailChanged && userRepository.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        }
        user.setDisplayName(TextUtils.strip(request.displayName()));
        user.setEmail(email);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.EMAIL_EXISTED, exception);
        }

        authAudit.profileUpdated(user, emailChanged);
        return toAuthUserResponse(user);
    }

    /** Changing the password signs the user out everywhere, including the current session. */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = requireCurrentUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            authAudit.passwordChangeFailed(user, "INVALID_CURRENT_PASSWORD");
            throw new AppException(ErrorCode.WRONG_CURRENT_PASSWORD);
        }
        PasswordPolicy.validate(request.newPassword(), request.passwordConfirmation());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        endAllSessions(user);
        authAudit.passwordChanged(user);
    }

    @Transactional
    public void logout() {
        User user = requireCurrentUser();
        endAllSessions(user);
        authAudit.loggedOut(user);
    }

    /** Invalidates every access token (token version) and refresh token of the user. */
    private void endAllSessions(User user) {
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
    }

    private User requireCurrentUser() {
        return userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
    }

    private AuthUserResponse toAuthUserResponse(User user) {
        return userMapper.toAuthUserResponse(user, patientLinkService.isOnboardingRequired(user));
    }

    private static AppException inactiveAccount(User user) {
        return new AppException(user.getStatus() == UserStatus.LOCKED
                ? ErrorCode.ACCOUNT_LOCKED
                : ErrorCode.ACCOUNT_DISABLED);
    }
}
