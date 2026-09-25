package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.config.LiteLLMProperties;
import com.medicalchatbot.backend.entity.LlmVirtualKey;
import com.medicalchatbot.backend.integration.client.LiteLLMAdminClient;
import com.medicalchatbot.backend.repository.LlmVirtualKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Doc spend/budget cost/ngay cua tung user tu AI Gateway (LiteLLM) — nguon su that
 * cho chi phi AI. Cache ngan (vai giay) de khong goi gateway o moi request quota.
 *
 * <p>Tra ve {@code null} khi gateway tat, user chua co virtual key, hoac loi goi —
 * de {@link QuotaService} roi ve so lieu cost tu {@code usage_logs}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiteLLMSpendService {

    private static final long CACHE_TTL_MILLIS = 5_000;

    private final LiteLLMProperties properties;
    private final LiteLLMAdminClient adminClient;
    private final LlmVirtualKeyRepository virtualKeyRepository;

    private final ConcurrentMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    /** Spend cost/ngay hien tai va budget cost/ngay theo gateway. */
    public record GatewaySpend(BigDecimal spendUsd, BigDecimal maxBudgetUsd) {
    }

    private record Cached(GatewaySpend spend, long expiresAtMillis) {
    }

    public GatewaySpend getSpendForUser(UUID userId) {
        if (!properties.isEnabled() || userId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(userId);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.spend();
        }
        GatewaySpend fresh = fetch(userId);
        if (fresh != null) {
            cache.put(userId, new Cached(fresh, now + CACHE_TTL_MILLIS));
        }
        return fresh;
    }

    private GatewaySpend fetch(UUID userId) {
        try {
            LlmVirtualKey key = virtualKeyRepository.findByUserId(userId).orElse(null);
            if (key == null) {
                return null;
            }
            JsonNode response = adminClient.getKeyInfo(key.getVirtualKey());
            JsonNode info = response != null ? response.path("info") : null;
            if (info == null || info.isMissingNode() || info.isNull()) {
                return null;
            }
            BigDecimal spend = new BigDecimal(info.path("spend").asText("0"));
            JsonNode maxBudgetNode = info.path("max_budget");
            BigDecimal maxBudget = maxBudgetNode.isNumber()
                    ? maxBudgetNode.decimalValue()
                    : key.getMaxBudgetUsd();
            return new GatewaySpend(spend, maxBudget);
        } catch (Exception ex) {
            log.warn("Could not read LiteLLM spend for user {}: {}", userId, ex.getMessage());
            return null;
        }
    }
}
