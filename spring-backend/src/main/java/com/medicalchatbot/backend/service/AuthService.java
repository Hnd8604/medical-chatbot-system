package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.request.AuthLoginRequest;
import com.medicalchatbot.backend.dto.request.AuthLinkPatientRequest;
import com.medicalchatbot.backend.dto.request.AuthRefreshRequest;
import com.medicalchatbot.backend.dto.request.AuthRegisterRequest;
import com.medicalchatbot.backend.dto.request.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.response.AuthLinkPatientResponse;
import com.medicalchatbot.backend.dto.response.AuthLoginResponse;
import com.medicalchatbot.backend.dto.response.AuthRefreshResponse;
import com.medicalchatbot.backend.dto.response.AuthRegisterResponse;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.ChangePasswordResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.AuthUserMapper;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final CurrentUserService currentUserService;
    private final PatientLinkService patientLinkService;
    private final AuthUserMapper authUserMapper;
    private final AuthAuditService authAuditService;

    @Transactional(readOnly = true)
    public AuthLoginResponse login(AuthLoginRequest request) {
        String credential = request.usernameOrEmail().strip();
        User user = userRepository.findByUsernameOrEmailIgnoreCase(credential)
                .orElseThrow(() -> invalidCredentials(credential));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials(credential);
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            authAuditService.loginFailed(user, credential, "LOCKED");
            throw new AppException(ErrorCode.ACCESS_DENIED, "Tai khoan da bi khoa.");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            authAuditService.loginFailed(user, credential, "DISABLED");
            throw new AppException(ErrorCode.ACCESS_DENIED, "Tai khoan da bi vo hieu hoa.");
        }

        authAuditService.loginSucceeded(user);
        return new AuthLoginResponse(
                jwtTokenService.generateToken(user),
                refreshTokenService.issue(user),
                "Bearer",
                jwtTokenService.expiresInSeconds(),
                authUserMapper.toResponse(user)
        );
    }

    /**
     * Xoay refresh token: nhận refresh token cũ, cấp cặp access + refresh token mới.
     * Refresh token cũ bị vô hiệu hóa ngay (rotation).
     */
    @Transactional
    public AuthRefreshResponse refresh(AuthRefreshRequest request) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(request.refreshToken());

        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> {
                    refreshTokenService.revokeAllForUser(rotation.userId());
                    return new AppException(ErrorCode.AUTHENTICATION_REQUIRED, "Nguoi dung khong ton tai.");
                });

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AppException(ErrorCode.ACCESS_DENIED, "Tai khoan da bi khoa.");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AppException(ErrorCode.ACCESS_DENIED, "Tai khoan da bi vo hieu hoa.");
        }
        // token_version đã đổi (vd user đã logout / đổi mật khẩu) => mọi phiên cũ hết hiệu lực.
        if (user.getTokenVersion() != rotation.tokenVersion()) {
            refreshTokenService.revokeAllForUser(user.getId());
            throw new AppException(ErrorCode.AUTHENTICATION_REQUIRED, "Phien dang nhap da het hieu luc.");
        }

        authAuditService.tokenRefreshed(user);
        return new AuthRefreshResponse(
                jwtTokenService.generateToken(user),
                refreshTokenService.issue(user),
                "Bearer",
                jwtTokenService.expiresInSeconds()
        );
    }

    @Transactional
    public AuthRegisterResponse register(AuthRegisterRequest request) {
        RegistrationInput input = normalizeRegistration(request);
        validateRegistration(input);

        if (userRepository.existsByUsernameIgnoreCase(input.username())) {
            throw new AppException(ErrorCode.CONFLICT, "Tên đăng nhập đã được sử dụng.");
        }
        if (userRepository.existsByEmailIgnoreCase(input.email())) {
            throw new AppException(ErrorCode.CONFLICT, "Email đã được sử dụng.");
        }

        QuotaPolicy quotaPolicy = quotaPolicyRepository.findByName("user_standard")
                .orElseThrow(() -> new AppException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "Chưa cấu hình quota policy mặc định 'user_standard'."
                ));

        User user = User.builder()
                .username(input.username())
                .email(input.email())
                .displayName(input.displayName())
                .passwordHash(passwordEncoder.encode(input.password()))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(0)
                .quotaPolicy(quotaPolicy)
                .build();

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.CONFLICT, "Tên đăng nhập hoặc email đã được sử dụng.", ex);
        }

        authAuditService.registrationSucceeded(user);
        return new AuthRegisterResponse("Đăng ký tài khoản thành công.", authUserMapper.toResponse(user));
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser() {
        return authUserMapper.toResponse(currentUserService.requireCurrentUser());
    }

    public AuthLinkPatientResponse linkPatient(AuthLinkPatientRequest request) {
        return patientLinkService.linkCurrentUser(request);
    }

    @Transactional
    public AuthUserResponse updateProfile(UpdateProfileRequest request) {
        User user = currentUserService.requireCurrentUser();

        String displayName = strip(request.displayName());
        String email = strip(request.email()).toLowerCase();

        if (displayName.length() < 2 || displayName.length() > 100) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Tên hiển thị phải có từ 2 đến 100 ký tự.");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Email không hợp lệ.");
        }

        boolean emailChanged = !email.equalsIgnoreCase(strip(user.getEmail()));
        if (emailChanged && userRepository.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.CONFLICT, "Email đã được sử dụng.");
        }

        user.setDisplayName(displayName);
        user.setEmail(email);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.CONFLICT, "Email đã được sử dụng.", ex);
        }

        authAuditService.profileUpdated(user, emailChanged);
        return authUserMapper.toResponse(user);
    }

    @Transactional
    public ChangePasswordResponse changePassword(ChangePasswordRequest request) {
        User user = currentUserService.requireCurrentUser();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            authAuditService.passwordChangeFailed(user, "INVALID_CURRENT_PASSWORD");
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Mật khẩu hiện tại không đúng.");
        }

        PasswordPolicy.validate(request.newPassword(), request.passwordConfirmation());

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Mật khẩu mới phải khác mật khẩu hiện tại.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Tăng token_version + thu hồi refresh token => mọi phiên đăng nhập cũ hết hiệu lực.
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(user.getId());

        authAuditService.passwordChangeSucceeded(user);
        return new ChangePasswordResponse("Đổi mật khẩu thành công. Vui lòng đăng nhập lại.");
    }

    @Transactional
    public void logout() {
        User user = currentUserService.requireCurrentUser();
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(user.getId());

        authAuditService.loggedOut(user);
    }

    private RegistrationInput normalizeRegistration(AuthRegisterRequest request) {
        return new RegistrationInput(
                strip(request.displayName()),
                strip(request.username()).toLowerCase(),
                strip(request.email()).toLowerCase(),
                request.password(),
                request.passwordConfirmation()
        );
    }

    private void validateRegistration(RegistrationInput input) {
        if (input.displayName().length() < 2 || input.displayName().length() > 100) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Tên hiển thị phải có từ 2 đến 100 ký tự.");
        }
        if (!input.username().matches("^[a-z0-9._-]{3,30}$")) {
            throw new AppException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang."
            );
        }
        if (!input.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Email không hợp lệ.");
        }
        PasswordPolicy.validate(input.password(), input.passwordConfirmation());
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private AppException invalidCredentials(String credential) {
        authAuditService.loginFailed(null, credential, "INVALID_CREDENTIALS");
        return new AppException(ErrorCode.AUTHENTICATION_REQUIRED, "Ten dang nhap/email hoac mat khau khong dung.");
    }

    private record RegistrationInput(
            String displayName,
            String username,
            String email,
            String password,
            String passwordConfirmation
    ) {
    }

}
