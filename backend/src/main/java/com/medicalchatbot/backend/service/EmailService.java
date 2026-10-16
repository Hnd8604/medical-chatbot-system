package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
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
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailService {

    ObjectProvider<JavaMailSender> mailSender;
    PasswordResetProperties properties;
    @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    TaskExecutor taskExecutor;

    /**
     * Fire-and-forget, and delivery failures are only logged: neither the forgot-password answer
     * nor how long it takes may reveal whether the email exists or whether sending worked.
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
        taskExecutor.execute(() -> deliver(sender, message, toEmail));
    }

    private static void deliver(JavaMailSender sender, SimpleMailMessage message, String toEmail) {
        try {
            sender.send(message);
        } catch (MailException exception) {
            log.error("Không thể gửi email đặt lại mật khẩu tới {}: {}", toEmail, exception.getMessage());
        }
    }
}
