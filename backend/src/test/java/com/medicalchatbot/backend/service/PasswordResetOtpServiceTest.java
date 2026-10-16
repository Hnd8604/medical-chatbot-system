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

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PasswordResetOtpServiceTest {

    private static final String EMAIL = "user@demo.local";
    private static final String OTP_KEY = "password_reset:otp:" + EMAIL;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private PasswordResetOtpService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetOtpService(redisTemplate,
                new PasswordResetProperties(10, 10, 5, 60, "no-reply@demo.local", "Medical Chatbot"));
    }

    @Test
    void issueCodeStoresSixDigitHashAndSetsCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey("password_reset:cooldown:" + EMAIL)).thenReturn(false);

        Optional<String> code = service.issueCode("User@Demo.local");

        assertTrue(code.orElseThrow().matches("\\d{6}"));
        // Stored as "attempts|hash", with no wrong attempt yet.
        verify(valueOps).set(eq(OTP_KEY), startsWith("0|"), eq(Duration.ofMinutes(10)));
        verify(valueOps).set(eq("password_reset:cooldown:" + EMAIL), eq("1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void issueCodeReturnsEmptyDuringCooldown() {
        when(redisTemplate.hasKey("password_reset:cooldown:" + EMAIL)).thenReturn(true);

        assertTrue(service.issueCode(EMAIL).isEmpty());
    }

    @Test
    void verifyCodeThenConsumeTicketRoundTrip() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        String rawCode = service.issueCode(EMAIL).orElseThrow();
        ArgumentCaptor<String> otpValue = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(OTP_KEY), otpValue.capture(), any(Duration.class));

        when(valueOps.get(OTP_KEY)).thenReturn(otpValue.getValue());
        String ticket = service.verifyCode(EMAIL, rawCode);
        verify(redisTemplate).delete(OTP_KEY);

        String ticketKey = "password_reset:ticket:" + ticket.substring(0, ticket.indexOf('.'));
        ArgumentCaptor<String> ticketValue = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(ticketKey), ticketValue.capture(), any(Duration.class));

        when(valueOps.get(ticketKey)).thenReturn(ticketValue.getValue());
        assertEquals(EMAIL, service.consumeTicket(ticket));
        verify(redisTemplate).delete(ticketKey);
    }

    @Test
    void verifyCodeRejectsWhenNoOtpStored() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(OTP_KEY)).thenReturn(null);

        AppException exception = assertThrows(AppException.class, () -> service.verifyCode(EMAIL, "123456"));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }

    @Test
    void wrongCodeCountsTheAttemptAndKeepsTheOriginalExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(OTP_KEY)).thenReturn("0|deadbeef");
        when(redisTemplate.getExpire(OTP_KEY, TimeUnit.SECONDS)).thenReturn(120L);

        assertThrows(AppException.class, () -> service.verifyCode(EMAIL, "000000"));

        verify(valueOps).set(eq(OTP_KEY), startsWith("1|"), eq(Duration.ofSeconds(120)));
    }

    @Test
    void codeIsInvalidatedAfterMaxAttempts() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Four wrong attempts so far (max = 5): this one reaches the limit.
        when(valueOps.get(OTP_KEY)).thenReturn("4|deadbeef");

        assertThrows(AppException.class, () -> service.verifyCode(EMAIL, "000000"));

        verify(redisTemplate).delete(OTP_KEY);
    }

    @Test
    void consumeTicketRejectsMalformedOrMissing() {
        assertThrows(AppException.class, () -> service.consumeTicket("khong-co-dau-cham"));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("password_reset:ticket:abc")).thenReturn(null);
        AppException exception = assertThrows(AppException.class, () -> service.consumeTicket("abc.secret"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }
}
