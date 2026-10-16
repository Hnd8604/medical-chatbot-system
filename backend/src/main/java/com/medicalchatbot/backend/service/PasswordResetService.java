package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.request.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.MessageResponse;
import com.medicalchatbot.backend.dto.response.ResetTicketResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.PasswordPolicy;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Three-step forgot-password flow: email a code → verify it for a ticket → set the new password. */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetOtpService otpService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuthAuditService authAudit;

    @Transactional
    public MessageResponse sendResetCode(ForgotPasswordRequest request) {
        String email = TextUtils.normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy tài khoản với email này."));
        if (!user.isActive()) {
            throw AppException.forbidden("Tài khoản không ở trạng thái hoạt động.");
        }

        // During the resend cooldown no new code is sent, but the answer stays the same.
        otpService.issueCode(email).ifPresent(code -> {
            emailService.sendPasswordResetCode(user.getEmail(), code);
            authAudit.passwordResetRequested(user);
        });
        return new MessageResponse("Đã gửi mã đặt lại mật khẩu tới email của bạn.");
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
                .orElseThrow(() -> AppException.invalid("Yêu cầu đặt lại mật khẩu không hợp lệ hoặc đã hết hạn."));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
        authAudit.passwordReset(user);
    }
}
