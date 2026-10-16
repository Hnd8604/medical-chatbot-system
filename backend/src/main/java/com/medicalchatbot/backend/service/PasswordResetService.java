package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.request.auth.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.MessageResponse;
import com.medicalchatbot.backend.dto.response.auth.ResetTicketResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.PasswordPolicy;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Three-step forgot-password flow: email a code → verify it for a ticket → set the new password. */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PasswordResetService {

    /** The only answer to a forgot-password request, so it never reveals whether an email has an account. */
    static final String RESET_CODE_SENT = "Nếu email đã được đăng ký, mã đặt lại mật khẩu đã được gửi tới email đó.";

    UserRepository userRepository;
    PasswordResetOtpService otpService;
    EmailService emailService;
    PasswordEncoder passwordEncoder;
    RefreshTokenService refreshTokenService;
    AuthAuditService authAudit;

    /**
     * Emails a code to an active account. Unknown, locked or disabled accounts and the resend
     * cooldown all get the same answer, and the email is sent in the background, so neither the
     * answer nor its timing tells whether the email is registered.
     */
    @Transactional
    public MessageResponse sendResetCode(ForgotPasswordRequest request) {
        String email = TextUtils.normalizeEmail(request.email());
        userRepository.findByEmailIgnoreCase(email)
                .filter(User::isActive)
                .ifPresent(user -> otpService.issueCode(email).ifPresent(code -> {
                    emailService.sendPasswordResetCode(user.getEmail(), code);
                    authAudit.passwordResetRequested(user);
                }));
        return new MessageResponse(RESET_CODE_SENT);
    }

    public ResetTicketResponse verifyResetCode(VerifyResetCodeRequest request) {
        return new ResetTicketResponse(otpService.verifyCode(request.email(), request.code()));
    }

    /** Setting a new password signs the user out of every session. */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        // Validate first so an unacceptable password does not burn the one-time ticket.
        PasswordPolicy.validate(request.newPassword(), request.passwordConfirmation());

        String email = otpService.consumeTicket(request.resetTicket());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AppException(ErrorCode.RESET_TICKET_INVALID));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
        authAudit.passwordReset(user);
    }
}
