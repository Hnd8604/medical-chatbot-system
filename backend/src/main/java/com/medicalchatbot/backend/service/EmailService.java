package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.config.PasswordResetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Gửi email cho ứng dụng. Khi chưa cấu hình SMTP ({@code spring.mail.host} rỗng nên không có
 * bean {@link JavaMailSender}), service rơi về dev fallback: chỉ log mã ra console để test local.
 */
@Slf4j
@Service
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordResetProperties properties;

    public EmailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            PasswordResetProperties properties
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    /**
     * Gửi mã đặt lại mật khẩu tới email. Lỗi gửi mail được nuốt và chỉ log nội bộ để không làm
     * lộ email tồn tại hay làm hỏng luồng quên mật khẩu (luôn trả thông điệp chung cho client).
     */
    public void sendPasswordResetCode(String toEmail, String code) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("[DEV] SMTP chua cau hinh. Ma dat lai mat khau cho {}: {}", toEmail, code);
            return;
        }

        long ttlMinutes = properties.otpTtlMinutes();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(String.format("%s <%s>", properties.fromName(), properties.fromAddress()));
        message.setTo(toEmail);
        message.setSubject("Ma dat lai mat khau - Medical Chatbot");
        message.setText(
                "Xin chao,\n\n"
                        + "Ma xac thuc de dat lai mat khau cua ban la: " + code + "\n"
                        + "Ma co hieu luc trong " + ttlMinutes + " phut.\n\n"
                        + "Neu ban khong yeu cau dat lai mat khau, vui long bo qua email nay.\n\n"
                        + "Medical Chatbot"
        );

        try {
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Khong the gui email dat lai mat khau toi {}: {}", toEmail, ex.getMessage());
        }
    }
}
