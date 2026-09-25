package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.config.LiteLLMProperties;
import com.medicalchatbot.backend.entity.LlmVirtualKey;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.integration.client.LiteLLMAdminClient;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.repository.LlmVirtualKeyRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.projection.QuotaPolicyProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmGatewayKeyServiceTest {

    @Mock
    private LiteLLMAdminClient adminClient;

    @Mock
    private LlmVirtualKeyRepository virtualKeyRepository;

    @Mock
    private QuotaPolicyRepository quotaPolicyRepository;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000501");

    private User user() {
        User user = new User(userId);
        user.setRole(UserRole.USER);
        return user;
    }

    private LlmGatewayKeyService service(boolean enabled) {
        LiteLLMProperties props = new LiteLLMProperties("http://localhost:4000",
                enabled ? "sk-master" : null, enabled);
        return new LlmGatewayKeyService(props, adminClient, virtualKeyRepository, quotaPolicyRepository);
    }

    @Test
    void returnsNullWhenGatewayDisabled() {
        String key = service(false).resolveUserKey(user());
        assertNull(key);
        verify(adminClient, never()).generateKey(any(), any(), any(), any());
    }

    @Test
    void provisionsNewKeyWithBudgetFromQuotaPolicy() {
        when(virtualKeyRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(quotaPolicyRepository.findByUserId(userId)).thenReturn(Optional.of(
                new QuotaPolicyProjection("user_standard", 30, 1000, new BigDecimal("0.50"))));
        when(adminClient.generateKey(eq(userId.toString()), any(), eq(new BigDecimal("0.50")), eq("USER")))
                .thenReturn("sk-new-key");

        String key = service(true).resolveUserKey(user());

        assertEquals("sk-new-key", key);
        verify(virtualKeyRepository).save(any(LlmVirtualKey.class));
    }

    @Test
    void reusesExistingKey() {
        LlmVirtualKey existing = LlmVirtualKey.builder()
                .userId(userId)
                .keyAlias("user-" + userId)
                .virtualKey("sk-existing")
                .maxBudgetUsd(new BigDecimal("0.50"))
                .budgetDuration("1d")
                .build();
        when(virtualKeyRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        String key = service(true).resolveUserKey(user());

        assertEquals("sk-existing", key);
        verify(adminClient, never()).generateKey(any(), any(), any(), any());
    }

    @Test
    void duplicateInsertRaceReusesWinnerKeyAndDeletesOrphan() {
        // 2 request dau tien song song: request kia da insert mapping truoc -> save
        // o day trung PK. Phai dung key da co va xoa key vua sinh o gateway.
        LlmVirtualKey winner = LlmVirtualKey.builder()
                .userId(userId)
                .keyAlias("user-" + userId)
                .virtualKey("sk-winner")
                .maxBudgetUsd(new BigDecimal("0.50"))
                .budgetDuration("1d")
                .build();
        when(virtualKeyRepository.findByUserId(userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(quotaPolicyRepository.findByUserId(userId)).thenReturn(Optional.of(
                new QuotaPolicyProjection("user_standard", 30, 1000, new BigDecimal("0.50"))));
        when(adminClient.generateKey(any(), any(), any(), any())).thenReturn("sk-loser");
        when(virtualKeyRepository.save(any(LlmVirtualKey.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        String key = service(true).resolveUserKey(user());

        assertEquals("sk-winner", key);
        verify(adminClient).deleteKey("sk-loser");
    }

    @Test
    void fallsBackToNullWhenGatewayCallFails() {
        when(virtualKeyRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(quotaPolicyRepository.findByUserId(userId)).thenReturn(Optional.of(
                new QuotaPolicyProjection("user_standard", 30, 1000, new BigDecimal("0.50"))));
        when(adminClient.generateKey(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("gateway down"));

        String key = service(true).resolveUserKey(user());

        assertNull(key);
    }
}
