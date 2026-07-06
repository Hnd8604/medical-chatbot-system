package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.config.LiteLLMProperties;
import com.medicalchatbot.backend.entity.LlmVirtualKey;
import com.medicalchatbot.backend.repository.LlmVirtualKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiteLLMSpendServiceTest {

    @Mock
    private LiteLLMAdminClient adminClient;

    @Mock
    private LlmVirtualKeyRepository virtualKeyRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000601");

    private LiteLLMSpendService service(boolean enabled) {
        LiteLLMProperties props = new LiteLLMProperties("http://localhost:4000",
                enabled ? "sk-master" : null, enabled);
        return new LiteLLMSpendService(props, adminClient, virtualKeyRepository);
    }

    private LlmVirtualKey key() {
        return LlmVirtualKey.builder()
                .userId(userId)
                .keyAlias("user-" + userId)
                .virtualKey("sk-user")
                .maxBudgetUsd(new BigDecimal("0.50"))
                .budgetDuration("1d")
                .build();
    }

    @Test
    void returnsNullWhenDisabled() {
        assertNull(service(false).getSpendForUser(userId));
    }

    @Test
    void readsSpendAndBudgetFromKeyInfo() throws Exception {
        JsonNode info = mapper.readTree("{\"info\":{\"spend\":0.1234,\"max_budget\":0.5}}");
        when(virtualKeyRepository.findByUserId(userId)).thenReturn(Optional.of(key()));
        when(adminClient.getKeyInfo("sk-user")).thenReturn(info);

        LiteLLMSpendService.GatewaySpend spend = service(true).getSpendForUser(userId);

        assertEquals(new BigDecimal("0.1234"), spend.spendUsd());
        assertEquals(new BigDecimal("0.5"), spend.maxBudgetUsd());
    }

    @Test
    void cachesResultWithinTtl() throws Exception {
        JsonNode info = mapper.readTree("{\"info\":{\"spend\":0.02,\"max_budget\":0.5}}");
        when(virtualKeyRepository.findByUserId(userId)).thenReturn(Optional.of(key()));
        when(adminClient.getKeyInfo("sk-user")).thenReturn(info);

        LiteLLMSpendService service = service(true);
        service.getSpendForUser(userId);
        service.getSpendForUser(userId);

        // Lan goi thu hai lay tu cache -> chi 1 lan cham gateway.
        verify(adminClient, times(1)).getKeyInfo("sk-user");
    }

    @Test
    void returnsNullWhenNoVirtualKey() {
        when(virtualKeyRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertNull(service(true).getSpendForUser(userId));
        verify(adminClient, org.mockito.Mockito.never()).getKeyInfo(any());
    }
}
