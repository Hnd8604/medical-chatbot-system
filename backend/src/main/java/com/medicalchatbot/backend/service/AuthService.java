package com.medicalchatbot.backend.service;

import java.util.Locale;
import java.util.regex.Pattern;

import com.medicalchatbot.backend.dto.request.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.LoginRequest;
import com.medicalchatbot.backend.dto.request.RefreshTokenRequest;
import com.medicalchatbot.backend.dto.request.RegisterRequest;
import com.medicalchatbot.backend.dto.request.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.LoginResponse;
import com.medicalchatbot.backend.dto.response.TokenResponse;
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
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login, token refresh, registration and management of the signed-in account. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{3,30}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int DISPLAY_NAME_MIN = 2;
    private static final int DISPLAY_NAME_MAX = 100;

    private final UserRepository userRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final CurrentUserService currentUserService;
    private final PatientLinkService patientLinkService;
    private final UserMapper userMapper;
    private final AuthAuditService authAudit;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String credential = request.usernameOrEmail().strip();
        User user = userRepository.findByUsernameOrEmailIgnoreCase(credential)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> {
                    authAudit.loginFailed(null, credential, "INVALID_CREDENTIALS");
                    return AppException.unauthorized("Tên đăng nhập/email hoặc mật khẩu không đúng.");
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
                    return AppException.unauthorized("Người dùng không tồn tại.");
                });
        if (!user.isActive()) {
            throw inactiveAccount(user);
        }
        // A changed token version (logout, password change) invalidates every older session.
        if (user.getTokenVersion() != owner.tokenVersion()) {
            refreshTokenService.revokeAll(user.getId());
            throw AppException.unauthorized("Phiên đăng nhập đã hết hiệu lực.");
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
        String displayName = validDisplayName(request.displayName());
        String username = TextUtils.strip(request.username()).toLowerCase(Locale.ROOT);
        String email = validEmail(request.email());
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw AppException.invalid(
                    "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
        }
        PasswordPolicy.validate(request.password(), request.passwordConfirmation());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw AppException.conflict("Tên đăng nhập đã được sử dụng.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw AppException.conflict("Email đã được sử dụng.");
        }

        QuotaPolicy quotaPolicy = quotaPolicyRepository.findByName(QuotaPolicy.DEFAULT_USER_POLICY)
                .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                        "Chưa cấu hình quota policy mặc định '" + QuotaPolicy.DEFAULT_USER_POLICY + "'."));
        User user = User.builder()
                .username(username)
                .email(email)
                .displayName(displayName)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .quotaPolicy(quotaPolicy)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.CONFLICT, "Tên đăng nhập hoặc email đã được sử dụng.", exception);
        }

        authAudit.registered(user);
        return toAuthUserResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser() {
        return toAuthUserResponse(currentUserService.requireCurrentUser());
    }

    @Transactional
    public AuthUserResponse updateProfile(UpdateProfileRequest request) {
        User user = currentUserService.requireCurrentUser();
        String displayName = validDisplayName(request.displayName());
        String email = validEmail(request.email());

        boolean emailChanged = !email.equalsIgnoreCase(TextUtils.strip(user.getEmail()));
        if (emailChanged && userRepository.existsByEmailIgnoreCase(email)) {
            throw AppException.conflict("Email đã được sử dụng.");
        }
        user.setDisplayName(displayName);
        user.setEmail(email);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.CONFLICT, "Email đã được sử dụng.", exception);
        }

        authAudit.profileUpdated(user, emailChanged);
        return toAuthUserResponse(user);
    }

    /** Changing the password signs the user out everywhere, including the current session. */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = currentUserService.requireCurrentUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            authAudit.passwordChangeFailed(user, "INVALID_CURRENT_PASSWORD");
            throw AppException.invalid("Mật khẩu hiện tại không đúng.");
        }
        PasswordPolicy.validate(request.newPassword(), request.passwordConfirmation());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw AppException.invalid("Mật khẩu mới phải khác mật khẩu hiện tại.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        endAllSessions(user);
        authAudit.passwordChanged(user);
    }

    @Transactional
    public void logout() {
        User user = currentUserService.requireCurrentUser();
        endAllSessions(user);
        authAudit.loggedOut(user);
    }

    /** Invalidates every access token (token version) and refresh token of the user. */
    private void endAllSessions(User user) {
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
    }

    private AuthUserResponse toAuthUserResponse(User user) {
        return userMapper.toAuthUserResponse(user, patientLinkService.isOnboardingRequired(user));
    }

    private static String validDisplayName(String value) {
        String displayName = TextUtils.strip(value);
        if (displayName.length() < DISPLAY_NAME_MIN || displayName.length() > DISPLAY_NAME_MAX) {
            throw AppException.invalid("Tên hiển thị phải có từ 2 đến 100 ký tự.");
        }
        return displayName;
    }

    private static String validEmail(String value) {
        String email = TextUtils.normalizeEmail(value);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw AppException.invalid("Email không hợp lệ.");
        }
        return email;
    }

    private static AppException inactiveAccount(User user) {
        return AppException.forbidden(user.getStatus() == UserStatus.LOCKED
                ? "Tài khoản đã bị khóa."
                : "Tài khoản đã bị vô hiệu hóa.");
    }
}
