package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.config.LiteLLMProperties;
import com.medicalchatbot.backend.entity.LlmVirtualKey;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.integration.client.LiteLLMAdminClient;
import com.medicalchatbot.backend.repository.LlmVirtualKeyRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.projection.QuotaPolicyProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cap phat va dong bo virtual key LiteLLM theo tung app_user.
 *
 * <p>Cap key <b>lazy</b> khi user chat lan dau: budget cost/ngay lay tu quota policy.
 * Moi loi goi gateway deu duoc bao boc de khong lam vo luong chat — khi that bai,
 * tra ve {@code null} va chatbot-service roi ve master key mac dinh.
 *
 * <p>Cac method chay voi {@code Propagation.NOT_SUPPORTED}: tx cua caller (vd tx chat)
 * duoc suspend trong luc goi HTTP gateway, va cac thao tac repository chay trong tx
 * ngan rieng — loi save o day (vd race trung PK) khong danh dau rollback-only
 * len tx chat cua caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayKeyService {

    /** Budget mac dinh khi user chua co quota policy (khong nen xay ra). */
    private static final BigDecimal DEFAULT_BUDGET_USD = new BigDecimal("1.00");

    private final LiteLLMProperties properties;
    private final LiteLLMAdminClient adminClient;
    private final LlmVirtualKeyRepository virtualKeyRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;

    /**
     * Tra ve virtual key cua user, cap moi neu chua co. Tra ve {@code null} khi gateway
     * tat hoac loi (chatbot-service se dung master key mac dinh).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String resolveUserKey(User user) {
        if (!properties.isEnabled()) {
            return null;
        }
        UUID userId = user.getId();
        try {
            LlmVirtualKey existing = virtualKeyRepository.findByUserId(userId).orElse(null);
            if (existing != null) {
                return existing.getVirtualKey();
            }
            BigDecimal budget = budgetForUser(userId);
            String alias = "user-" + userId;
            String key = adminClient.generateKey(userId.toString(), alias, budget, user.getRole().name());
            try {
                virtualKeyRepository.save(LlmVirtualKey.builder()
                        .userId(userId)
                        .keyAlias(alias)
                        .virtualKey(key)
                        .maxBudgetUsd(budget)
                        .budgetDuration("1d")
                        .build());
            } catch (DataIntegrityViolationException race) {
                // 2 request dau tien cua cung user chay song song: request kia da luu
                // mapping truoc. Dung key da thang cuoc; xoa key vua sinh de khong
                // tich luy key mo coi o gateway.
                log.info("Concurrent key provisioning for user {}; reusing existing key", userId);
                tryDeleteOrphanKey(key);
                return virtualKeyRepository.findByUserId(userId)
                        .map(LlmVirtualKey::getVirtualKey)
                        .orElse(null);
            }
            log.info("Provisioned LiteLLM virtual key for user {} with daily budget {} USD", userId, budget);
            return key;
        } catch (Exception ex) {
            log.warn("Could not resolve LiteLLM virtual key for user {}; falling back to master key. Cause: {}",
                    userId, ex.getMessage());
            return null;
        }
    }

    private void tryDeleteOrphanKey(String key) {
        try {
            adminClient.deleteKey(key);
        } catch (Exception ex) {
            log.warn("Could not delete orphan LiteLLM key: {}", ex.getMessage());
        }
    }

    /**
     * Dong bo lai budget cho tat ca virtual key thuoc mot quota policy (best-effort).
     * Goi khi admin sua policy. Khong nem loi ra ngoai.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void syncBudgetForPolicy(UUID policyId, BigDecimal newBudgetUsd) {
        if (!properties.isEnabled() || policyId == null || newBudgetUsd == null) {
            return;
        }
        List<LlmVirtualKey> keys = virtualKeyRepository.findAllByQuotaPolicyId(policyId);
        for (LlmVirtualKey key : keys) {
            try {
                adminClient.updateKeyBudget(key.getVirtualKey(), newBudgetUsd);
                key.setMaxBudgetUsd(newBudgetUsd);
                virtualKeyRepository.save(key);
            } catch (Exception ex) {
                log.warn("Failed to sync budget for virtual key of user {}: {}", key.getUserId(), ex.getMessage());
            }
        }
    }

    private BigDecimal budgetForUser(UUID userId) {
        return quotaPolicyRepository.findByUserId(userId)
                .map(QuotaPolicyProjection::dailyCostLimitUsd)
                .orElse(DEFAULT_BUDGET_USD);
    }
}
