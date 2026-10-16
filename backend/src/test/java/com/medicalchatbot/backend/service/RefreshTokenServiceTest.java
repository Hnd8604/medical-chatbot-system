package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Duration TTL = Duration.ofMinutes(10080);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SetOperations<String, String> setOps;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(redisTemplate, new JwtProperties("dev-secret", 30, 10080));
    }

    @Test
    void issueStoresOnlyTheHashOfTheSecret() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        User user = user(2);

        String token = service.issue(user);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), stored.capture(), eq(TTL));
        String secret = token.substring(token.indexOf('.') + 1);
        assertTrue(stored.getValue().startsWith(user.getId() + "|2|"));
        assertFalse(stored.getValue().contains(secret));
        verify(setOps).add(eq("refresh_token:user:" + user.getId()), anyString());
    }

    @Test
    void rotateReturnsTheOwnerOnceAndRejectsReuse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        User user = user(7);
        String token = service.issue(user);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key.capture(), value.capture(), eq(TTL));

        // GETDEL hands the stored value out once; Redis has nothing left for the replay.
        when(valueOps.getAndDelete(key.getValue())).thenReturn(value.getValue()).thenReturn(null);
        RefreshTokenService.TokenOwner owner = service.rotate(token);

        assertEquals(user.getId(), owner.userId());
        assertEquals(7, owner.tokenVersion());
        verify(setOps).remove(eq("refresh_token:user:" + user.getId()), eq(key.getValue().substring("refresh_token:".length())));

        AppException exception = assertThrows(AppException.class, () -> service.rotate(token));
        assertEquals(ErrorCode.REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void rotateRejectsMalformedToken() {
        AppException exception = assertThrows(AppException.class, () -> service.rotate("khong-co-dau-cham"));

        assertEquals(ErrorCode.REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void rotateRejectsTamperedSecret() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(valueOps.getAndDelete("refresh_token:some-jti")).thenReturn(UUID.randomUUID() + "|0|deadbeef");

        AppException exception = assertThrows(AppException.class, () -> service.rotate("some-jti.wrong-secret"));

        assertEquals(ErrorCode.REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void revokeAllDeletesEveryTokenOfTheUser() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        UUID userId = UUID.randomUUID();
        when(setOps.members("refresh_token:user:" + userId)).thenReturn(Set.of("a", "b"));

        service.revokeAll(userId);

        verify(redisTemplate).delete("refresh_token:a");
        verify(redisTemplate).delete("refresh_token:b");
        verify(redisTemplate).delete("refresh_token:user:" + userId);
    }

    private static User user(int tokenVersion) {
        User user = TestUsers.user(UserRole.USER);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
