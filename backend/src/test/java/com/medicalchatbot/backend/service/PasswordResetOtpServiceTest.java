package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.medicalchatbot.backend.config.PasswordResetProperties;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PasswordResetOtpServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final PasswordResetProperties properties =
            new PasswordResetProperties(10, 10, 5, 60, "no-reply@demo.local", "Medical Chatbot");

    private PasswordResetOtpService service() {
        return new PasswordResetOtpService(redisTemplate, properties);
    }

    @Test
    void issueCodeStoresSixDigitHashAndSetsCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey("password_reset:cooldown:user@demo.local")).thenReturn(false);

        Optional<String> code = service().issueCode("User@Demo.local");

        assertTrue(code.isPresent());
        assertEquals(6, code.get().length());
        assertTrue(code.get().matches("\\d{6}"));
        // OTP lưu dạng "attempts|hash", chưa có lần nhập sai nào.
        verify(valueOps).set(eq("password_reset:otp:user@demo.local"), startsWith("0|"), eq(Duration.ofMinutes(10)));
        verify(valueOps).set(eq("password_reset:cooldown:user@demo.local"), eq("1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void issueCodeReturnsEmptyDuringCooldown() {
        when(redisTemplate.hasKey("password_reset:cooldown:user@demo.local")).thenReturn(true);

        assertTrue(service().issueCode("user@demo.local").isEmpty());
    }

    @Test
    void verifyCodeThenConsumeTicketRoundTrip() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        PasswordResetOtpService service = service();
        String email = "user@demo.local";
        String otpKey = "password_reset:otp:" + email;

        // 1) Phát mã và bắt lại giá trị OTP đã lưu (attempts|hash).
        String rawCode = service.issueCode(email).orElseThrow();
        ArgumentCaptor<String> otpValue = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(otpKey), otpValue.capture(), any(Duration.class));

        // 2) Xác thực mã đúng => xóa OTP và cấp ticket.
        when(valueOps.get(otpKey)).thenReturn(otpValue.getValue());
        String ticket = service.verifyCode(email, rawCode);
        assertTrue(ticket.contains("."));
        verify(redisTemplate).delete(otpKey);

        String jti = ticket.substring(0, ticket.indexOf('.'));
        String ticketKey = "password_reset:ticket:" + jti;
        ArgumentCaptor<String> ticketValue = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(ticketKey), ticketValue.capture(), any(Duration.class));

        // 3) Tiêu thụ ticket đúng => trả email và xóa ticket (dùng một lần).
        when(valueOps.get(ticketKey)).thenReturn(ticketValue.getValue());
        assertEquals(email, service.consumeTicket(ticket));
        verify(redisTemplate).delete(ticketKey);
    }

    @Test
    void verifyCodeRejectsWhenNoOtpStored() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("password_reset:otp:user@demo.local")).thenReturn(null);

        AppException ex = assertThrows(
                AppException.class,
                () -> service().verifyCode("user@demo.local", "123456")
        );
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.getErrorCode());
    }

    @Test
    void verifyCodeWrongCodeIncrementsAttemptsAndKeepsTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String otpKey = "password_reset:otp:user@demo.local";
        when(valueOps.get(otpKey)).thenReturn("0|deadbeef");
        when(redisTemplate.getExpire(otpKey, TimeUnit.SECONDS)).thenReturn(120L);

        assertThrows(AppException.class, () -> service().verifyCode("user@demo.local", "000000"));

        verify(valueOps).set(eq(otpKey), startsWith("1|"), eq(Duration.ofSeconds(120)));
    }

    @Test
    void verifyCodeInvalidatesAfterMaxAttempts() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String otpKey = "password_reset:otp:user@demo.local";
        // Đã sai 4 lần (max = 5); lần sai này chạm ngưỡng => xóa mã.
        when(valueOps.get(otpKey)).thenReturn("4|deadbeef");

        assertThrows(AppException.class, () -> service().verifyCode("user@demo.local", "000000"));

        verify(redisTemplate).delete(otpKey);
    }

    @Test
    void consumeTicketRejectsMalformedOrMissing() {
        assertThrows(AppException.class, () -> service().consumeTicket("khong-co-dau-cham"));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("password_reset:ticket:abc")).thenReturn(null);
        AppException ex = assertThrows(
                AppException.class,
                () -> service().consumeTicket("abc.secret")
        );
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.getErrorCode());
    }
}
