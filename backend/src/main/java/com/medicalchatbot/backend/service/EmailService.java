package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends application emails. Without SMTP configuration ({@code spring.mail.host} empty, so no
 * {@link JavaMailSender} bean) the code is logged instead, which is what local development uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final PasswordResetProperties properties;

    /**
     * Delivery failures are only logged: the forgot-password answer must not reveal whether the
     * email exists or whether sending worked.
     */
    public void sendPasswordResetCode(String toEmail, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("[DEV] SMTP chưa cấu hình. Mã đặt lại mật khẩu cho {}: {}", toEmail, code);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(String.format("%s <%s>", properties.fromName(), properties.fromAddress()));
        message.setTo(toEmail);
        message.setSubject("Mã đặt lại mật khẩu - Medical Chatbot");
        message.setText("""
                Xin chào,

                Mã xác thực để đặt lại mật khẩu của bạn là: %s
                Mã có hiệu lực trong %d phút.

                Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này.

                Medical Chatbot
                """.formatted(code, properties.otpTtlMinutes()));
        try {
            sender.send(message);
        } catch (MailException exception) {
            log.error("Không thể gửi email đặt lại mật khẩu tới {}: {}", toEmail, exception.getMessage());
        }
    }
}
