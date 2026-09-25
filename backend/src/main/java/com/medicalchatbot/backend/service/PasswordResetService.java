package com.medicalchatbot.backend.service;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.ForgotPasswordResponse;
import com.medicalchatbot.backend.dto.response.ResetPasswordResponse;
import com.medicalchatbot.backend.dto.response.VerifyResetCodeResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điều phối luồng quên mật khẩu 3 bước: gửi mã OTP → xác thực mã lấy ticket → đặt lại mật khẩu.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetOtpService otpService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy tài khoản với email này."
                ));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCESS_DENIED, "Tài khoản không ở trạng thái hoạt động.");
        }

        Optional<String> code = otpService.issueCode(email);
        code.ifPresent(value -> {
            emailService.sendPasswordResetCode(user.getEmail(), value);
            logAudit(user, "PASSWORD_RESET_REQUEST");
        });
        return new ForgotPasswordResponse("Đã gửi mã đặt lại mật khẩu tới email của bạn.");
    }

    @Transactional(readOnly = true)
    public VerifyResetCodeResponse verifyResetCode(VerifyResetCodeRequest request) {
        String ticket = otpService.verifyCode(normalizeEmail(request.email()), request.code());
        return new VerifyResetCodeResponse(ticket, "Mã xác thực hợp lệ.");
    }

    @Transactional
    public ResetPasswordResponse resetPassword(ResetPasswordRequest request) {
        // Kiểm tra mật khẩu trước khi tiêu thụ ticket để mật khẩu lỗi không làm mất ticket.
        PasswordPolicy.validate(request.newPassword(), request.passwordConfirmation());

        String email = otpService.consumeTicket(request.resetTicket());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AppException(
                        ErrorCode.INVALID_ARGUMENT,
                        "Yeu cau dat lai mat khau khong hop le hoac da het han."
                ));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Tăng token_version + thu hồi refresh token => mọi phiên đăng nhập cũ hết hiệu lực.
        user.incrementTokenVersion();
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(user.getId());

        logAudit(user, "PASSWORD_RESET_SUCCESS");
        return new ResetPasswordResponse("Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại.");
    }

    private void logAudit(User user, String action) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "password_reset");
        metadata.put("result", "success");
        auditLogRepository.save(user, null, action, "app_user", user.getId().toString(), metadata);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase();
    }
}
