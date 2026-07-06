package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.QuotaPolicyUpsertRequest;
import com.medicalchatbot.backend.dto.response.QuotaPolicyAdminResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class QuotaPolicyAdminService {

    private final QuotaPolicyRepository quotaPolicyRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final LlmGatewayKeyService llmGatewayKeyService;

    public QuotaPolicyAdminService(
            QuotaPolicyRepository quotaPolicyRepository,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            LlmGatewayKeyService llmGatewayKeyService
    ) {
        this.quotaPolicyRepository = quotaPolicyRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.llmGatewayKeyService = llmGatewayKeyService;
    }

    @Transactional(readOnly = true)
    public List<QuotaPolicyAdminResponse> list() {
        return quotaPolicyRepository.findAll().stream()
                .map(QuotaPolicyAdminResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public QuotaPolicyAdminResponse get(UUID id) {
        return QuotaPolicyAdminResponse.from(getEntity(id));
    }

    @Transactional
    public QuotaPolicyAdminResponse create(QuotaPolicyUpsertRequest request) {
        if (quotaPolicyRepository.existsByName(request.name())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Quota policy đã tồn tại với tên: " + request.name()
            );
        }
        QuotaPolicy policy = new QuotaPolicy(
                request.name(),
                request.dailyRequestLimit(),
                request.dailyTokenLimit(),
                request.dailyCostLimitUsd(),
                request.rateLimitPerMinute()
        );
        QuotaPolicy saved = quotaPolicyRepository.save(policy);
        audit("QUOTA_POLICY_CREATED", saved);
        return QuotaPolicyAdminResponse.from(saved);
    }

    @Transactional
    @CacheEvict(value = "rateLimitConfig", allEntries = true)
    public QuotaPolicyAdminResponse update(UUID id, QuotaPolicyUpsertRequest request) {
        QuotaPolicy policy = getEntity(id);
        quotaPolicyRepository.findByName(request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Quota policy đã tồn tại với tên: " + request.name()
                    );
                });
        policy.applyUpdate(
                request.name(),
                request.dailyRequestLimit(),
                request.dailyTokenLimit(),
                request.dailyCostLimitUsd(),
                request.rateLimitPerMinute()
        );
        QuotaPolicy saved = quotaPolicyRepository.save(policy);
        // Budget cost/ngay o gateway phai theo policy moi cho cac user da co virtual key.
        llmGatewayKeyService.syncBudgetForPolicy(saved.getId(), saved.getDailyCostLimitUsd());
        audit("QUOTA_POLICY_UPDATED", saved);
        return QuotaPolicyAdminResponse.from(saved);
    }

    @Transactional
    @CacheEvict(value = "rateLimitConfig", allEntries = true)
    public void delete(UUID id) {
        QuotaPolicy policy = getEntity(id);
        long referencing = userRepository.countByQuotaPolicyId(id);
        if (referencing > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Không thể xoá: còn " + referencing + " người dùng đang dùng policy này."
            );
        }
        quotaPolicyRepository.delete(policy);
        audit("QUOTA_POLICY_DELETED", policy);
    }

    private QuotaPolicy getEntity(UUID id) {
        return quotaPolicyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy quota policy: " + id
                ));
    }

    private void audit(String action, QuotaPolicy policy) {
        try {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("name", policy.getName());
            metadata.put("daily_request_limit", policy.getDailyRequestLimit());
            metadata.put("daily_token_limit", policy.getDailyTokenLimit());
            metadata.put("daily_cost_limit_usd", policy.getDailyCostLimitUsd());
            metadata.put("rate_limit_per_minute", policy.getRateLimitPerMinute());
            auditLogRepository.save(
                    null,
                    null,
                    action,
                    "quota_policy",
                    policy.getId() != null ? policy.getId().toString() : null,
                    metadata
            );
        } catch (Exception ex) {
            log.error("Failed to write audit log {} for quota policy", action, ex);
        }
    }
}
