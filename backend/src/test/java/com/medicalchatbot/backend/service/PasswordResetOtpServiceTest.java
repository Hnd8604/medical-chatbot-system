package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.util.SecureTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Unit tests with a mocked Redis. The atomic check of the code itself runs as a Lua script inside
 * Redis and is covered by {@link RedisAtomicityTest}.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetOtpServiceTest {

    private static final String EMAIL = "user@demo.local";
    private static final String OTP_KEY = "password_reset:otp:" + EMAIL;
    private static final String COOLDOWN_KEY = "password_reset:cooldown:" + EMAIL;

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
    void issueCodeStoresSixDigitHashAndStartsTheCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", Duration.ofSeconds(60))).thenReturn(true);

        Optional<String> code = service.issueCode("User@Demo.local");

        assertTrue(code.orElseThrow().matches("\\d{6}"));
        // Stored as "attempts|hash", with no wrong attempt yet.
        verify(valueOps).set(OTP_KEY, "0|" + SecureTokens.sha256Hex(code.get()), Duration.ofMinutes(10));
    }

    @Test
    void issueCodeReturnsEmptyDuringCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", Duration.ofSeconds(60))).thenReturn(false);

        assertTrue(service.issueCode(EMAIL).isEmpty());
        verify(valueOps, never()).set(eq(OTP_KEY), anyString(), any(Duration.class));
    }

    @Test
    void verifyCodeThenConsumeTicketRoundTrip() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        String rawCode = service.issueCode(EMAIL).orElseThrow();

        // The script receives the hash of the submitted code and the attempt limit.
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(OTP_KEY)),
                eq(SecureTokens.sha256Hex(rawCode)), eq("5"))).thenReturn(1L);
        String ticket = service.verifyCode(EMAIL, " " + rawCode + " ");

        String ticketKey = "password_reset:ticket:" + ticket.substring(0, ticket.indexOf('.'));
        ArgumentCaptor<String> ticketValue = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(ticketKey), ticketValue.capture(), eq(Duration.ofMinutes(10)));
        assertTrue(ticketValue.getValue().endsWith("|" + EMAIL));

        when(valueOps.getAndDelete(ticketKey)).thenReturn(ticketValue.getValue());
        assertEquals(EMAIL, service.consumeTicket(ticket));
    }

    @Test
    void wrongOrExpiredCodeIsRejectedWithoutATicket() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(OTP_KEY)), anyString(), eq("5")))
                .thenReturn(0L);

        AppException exception = assertThrows(AppException.class, () -> service.verifyCode(EMAIL, "123456"));

        assertEquals(ErrorCode.RESET_CODE_INVALID, exception.getErrorCode());
        verify(valueOps, never()).set(startsWith("password_reset:ticket:"), anyString(), any(Duration.class));
    }

    @Test
    void consumeTicketRejectsMalformedMissingOrTamperedTickets() {
        assertThrows(AppException.class, () -> service.consumeTicket("khong-co-dau-cham"));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("password_reset:ticket:abc")).thenReturn(null);
        AppException missing = assertThrows(AppException.class, () -> service.consumeTicket("abc.secret"));
        assertEquals(ErrorCode.RESET_TICKET_INVALID, missing.getErrorCode());

        when(valueOps.getAndDelete("password_reset:ticket:def")).thenReturn("deadbeef|" + EMAIL);
        assertThrows(AppException.class, () -> service.consumeTicket("def.wrong-secret"));
    }
}
