package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.QuotaPolicyRequest;
import com.medicalchatbot.backend.dto.response.QuotaPolicyResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.mapper.QuotaPolicyMapper;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administration of quota policies (daily limits and rate limits assigned to users). */
@Service
@RequiredArgsConstructor
public class QuotaPolicyService {

    private final QuotaPolicyRepository quotaPolicyRepository;
    private final UserRepository userRepository;
    private final QuotaPolicyMapper quotaPolicyMapper;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<QuotaPolicyResponse> list() {
        return quotaPolicyRepository.findAll().stream().map(quotaPolicyMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public QuotaPolicyResponse get(UUID id) {
        return quotaPolicyMapper.toResponse(requirePolicy(id));
    }

    @Transactional
    public QuotaPolicyResponse create(QuotaPolicyRequest request) {
        if (quotaPolicyRepository.existsByName(request.name())) {
            throw duplicateName(request.name());
        }
        QuotaPolicy policy = QuotaPolicy.builder().build();
        quotaPolicyMapper.applyRequest(request, policy);
        QuotaPolicy saved = quotaPolicyRepository.save(policy);
        audit(AuditAction.QUOTA_POLICY_CREATED, saved);
        return quotaPolicyMapper.toResponse(saved);
    }

    @Transactional
    public QuotaPolicyResponse update(UUID id, QuotaPolicyRequest request) {
        QuotaPolicy policy = requirePolicy(id);
        quotaPolicyRepository.findByName(request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw duplicateName(request.name());
                });
        quotaPolicyMapper.applyRequest(request, policy);
        audit(AuditAction.QUOTA_POLICY_UPDATED, policy);
        return quotaPolicyMapper.toResponse(policy);
    }

    @Transactional
    public void delete(UUID id) {
        QuotaPolicy policy = requirePolicy(id);
        long assignedUsers = userRepository.countByQuotaPolicyId(id);
        if (assignedUsers > 0) {
            throw AppException.conflict("Không thể xóa: còn " + assignedUsers + " người dùng đang dùng chính sách này.");
        }
        quotaPolicyRepository.delete(policy);
        audit(AuditAction.QUOTA_POLICY_DELETED, policy);
    }

    private QuotaPolicy requirePolicy(UUID id) {
        return quotaPolicyRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chính sách quota."));
    }

    private void audit(AuditAction action, QuotaPolicy policy) {
        ObjectNode metadata = auditService.metadata()
                .put("name", policy.getName())
                .put("daily_request_limit", policy.getDailyRequestLimit())
                .put("daily_token_limit", policy.getDailyTokenLimit())
                .put("daily_cost_limit_usd", policy.getDailyCostLimitUsd())
                .put("rate_limit_per_minute", policy.getRateLimitPerMinute());
        auditService.record(currentUserService.requireCurrentUser(), action, AuditResource.QUOTA_POLICY,
                String.valueOf(policy.getId()), metadata);
    }

    private static AppException duplicateName(String name) {
        return AppException.conflict("Đã có chính sách quota tên: " + name);
    }
}
