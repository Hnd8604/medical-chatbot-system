package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.medicalchatbot.backend.config.JwtProperties;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
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

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SetOperations<String, String> setOps;

    private final JwtProperties jwtProperties = new JwtProperties("dev-secret", 30, 10080);

    private RefreshTokenService service() {
        return new RefreshTokenService(redisTemplate, jwtProperties);
    }

    private User user(UUID id, int tokenVersion) {
        User user = new User(id);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "tokenVersion", tokenVersion);
        return user;
    }

    @Test
    void issueReturnsTokenAndStoresHashInRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000501");

        String token = service().issue(user(userId, 2));

        // Token thô có dạng {jti}.{secret} và secret không bị lưu thẳng.
        assertTrue(token.contains("."));
        verify(valueOps).set(anyString(), anyString(), eq(java.time.Duration.ofMinutes(10080)));
        verify(setOps).add(eq("refresh_token:user:" + userId), anyString());
    }

    @Test
    void rotateReturnsOwnerAndDeletesOldTokenThenRejectsReuse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000502");

        RefreshTokenService service = service();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        String token = service.issue(user(userId, 7));
        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), eq(java.time.Duration.ofMinutes(10080)));

        // Lần rotate đầu: token hợp lệ, trả đúng chủ sở hữu và token_version.
        when(valueOps.get(keyCaptor.getValue())).thenReturn(valueCaptor.getValue());
        RefreshTokenService.RotationResult result = service.rotate(token);

        assertEquals(userId, result.userId());
        assertEquals(7, result.tokenVersion());
        verify(redisTemplate).delete(keyCaptor.getValue());

        // Sau khi đã xoay, token cũ không còn trong Redis => dùng lại bị từ chối.
        when(valueOps.get(keyCaptor.getValue())).thenReturn(null);
        AppException ex = assertThrows(
                AppException.class,
                () -> service.rotate(token)
        );
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, ex.getErrorCode());
    }

    @Test
    void rotateRejectsMalformedToken() {
        AppException ex = assertThrows(
                AppException.class,
                () -> service().rotate("khong-co-dau-cham")
        );
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, ex.getErrorCode());
    }

    @Test
    void rotateRejectsTamperedSecret() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000503");
        // jti tồn tại nhưng secret gửi lên không khớp hash đã lưu.
        when(valueOps.get("refresh_token:" + "some-jti"))
                .thenReturn(userId + "|0|deadbeef");

        AppException ex = assertThrows(
                AppException.class,
                () -> service().rotate("some-jti.sai-secret")
        );
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, ex.getErrorCode());
    }
}
