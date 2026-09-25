package com.medicalchatbot.backend.integration.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client goi cac endpoint quan ly cua LiteLLM AI Gateway bang master key:
 * sinh/cap nhat virtual key theo user, doc spend/budget, chan/mo key.
 *
 * <p>Tat ca request dung header Authorization: Bearer &lt;master_key&gt; da duoc gan
 * san trong bean {@code litellmRestClient}.
 */
@Slf4j
@Component
public class LiteLLMAdminClient {

    /** Cac alias model khai bao trong infra/litellm/config.yaml. */
    static final List<String> ALLOWED_MODELS = List.of(
            "gpt-4o-mini",
            "gpt-4.1-mini",
            "groq-llama-8b",
            "groq-llama-70b");

    private final RestClient litellmRestClient;

    public LiteLLMAdminClient(@Qualifier("litellmRestClient") RestClient litellmRestClient) {
        this.litellmRestClient = litellmRestClient;
    }

    /**
     * Sinh mot virtual key gan voi {@code userId}, budget theo cost/ngay.
     *
     * @return gia tri key (dang {@code sk-...}) de client dung khi goi LLM.
     */
    public String generateKey(String userId, String keyAlias, BigDecimal maxBudgetUsd, String role) {
        Map<String, Object> body = Map.of(
                "user_id", userId,
                "key_alias", keyAlias,
                "max_budget", maxBudgetUsd,
                "budget_duration", "1d",
                "models", ALLOWED_MODELS,
                "metadata", Map.of("role", role == null ? "" : role));

        JsonNode response = litellmRestClient.post()
                .uri("/key/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String key = response != null ? response.path("key").asText(null) : null;
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("LiteLLM /key/generate did not return a key for user " + userId);
        }
        return key;
    }

    /** Cap nhat budget cost/ngay cho mot virtual key da ton tai. */
    public void updateKeyBudget(String key, BigDecimal maxBudgetUsd) {
        Map<String, Object> body = Map.of(
                "key", key,
                "max_budget", maxBudgetUsd,
                "budget_duration", "1d");
        litellmRestClient.post()
                .uri("/key/update")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Doc thong tin key: {@code info.spend}, {@code info.max_budget}. Tra ve null neu
     * gateway khong biet key (vd bi xoa).
     */
    public JsonNode getKeyInfo(String key) {
        return litellmRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/key/info").queryParam("key", key).build())
                .retrieve()
                .body(JsonNode.class);
    }

    /** Xoa han mot virtual key o gateway (don dep key mo coi khi provisioning race). */
    public void deleteKey(String key) {
        litellmRestClient.post()
                .uri("/key/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("keys", List.of(key)))
                .retrieve()
                .toBodilessEntity();
    }

    public void blockKey(String key) {
        litellmRestClient.post()
                .uri("/key/block")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("key", key))
                .retrieve()
                .toBodilessEntity();
    }

    public void unblockKey(String key) {
        litellmRestClient.post()
                .uri("/key/unblock")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("key", key))
                .retrieve()
                .toBodilessEntity();
    }
}
