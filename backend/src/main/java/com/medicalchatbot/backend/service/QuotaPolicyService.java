package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.admin.QuotaPolicyRequest;
import com.medicalchatbot.backend.dto.response.admin.QuotaPolicyResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.QuotaPolicyMapper;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.SecurityUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administration of quota policies (daily limits and rate limits assigned to users). */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasRole('ADMIN')")
public class QuotaPolicyService {

    QuotaPolicyRepository quotaPolicyRepository;
    UserRepository userRepository;
    QuotaPolicyMapper quotaPolicyMapper;
    AuditService auditService;

    @Transactional(readOnly = true)
    public List<QuotaPolicyResponse> list() {
        return quotaPolicyRepository.findAll().stream().map(quotaPolicyMapper::toQuotaPolicyResponse).toList();
    }

    @Transactional(readOnly = true)
    public QuotaPolicyResponse get(UUID id) {
        return quotaPolicyMapper.toQuotaPolicyResponse(requirePolicy(id));
    }

    @Transactional
    public QuotaPolicyResponse create(QuotaPolicyRequest request) {
        if (quotaPolicyRepository.existsByName(request.name())) {
            throw duplicateName(request.name());
        }
        QuotaPolicy saved = quotaPolicyRepository.save(quotaPolicyMapper.toQuotaPolicy(request));
        audit(AuditAction.QUOTA_POLICY_CREATED, saved);
        return quotaPolicyMapper.toQuotaPolicyResponse(saved);
    }

    @Transactional
    public QuotaPolicyResponse update(UUID id, QuotaPolicyRequest request) {
        QuotaPolicy policy = requirePolicy(id);
        quotaPolicyRepository.findByName(request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw duplicateName(request.name());
                });
        quotaPolicyMapper.updateQuotaPolicy(policy, request);
        audit(AuditAction.QUOTA_POLICY_UPDATED, policy);
        return quotaPolicyMapper.toQuotaPolicyResponse(policy);
    }

    @Transactional
    public void delete(UUID id) {
        QuotaPolicy policy = requirePolicy(id);
        long assignedUsers = userRepository.countByQuotaPolicyId(id);
        if (assignedUsers > 0) {
            throw new AppException(ErrorCode.QUOTA_POLICY_IN_USE,
                    "Không thể xóa: còn " + assignedUsers + " người dùng đang dùng chính sách này.");
        }
        quotaPolicyRepository.delete(policy);
        audit(AuditAction.QUOTA_POLICY_DELETED, policy);
    }

    private QuotaPolicy requirePolicy(UUID id) {
        return quotaPolicyRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.QUOTA_POLICY_NOT_EXISTED));
    }

    private void audit(AuditAction action, QuotaPolicy policy) {
        ObjectNode metadata = auditService.metadata()
                .put("name", policy.getName())
                .put("daily_request_limit", policy.getDailyRequestLimit())
                .put("daily_token_limit", policy.getDailyTokenLimit())
                .put("daily_cost_limit_usd", policy.getDailyCostLimitUsd())
                .put("rate_limit_per_minute", policy.getRateLimitPerMinute());
        auditService.record(userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED)), action, AuditResource.QUOTA_POLICY,
                String.valueOf(policy.getId()), metadata);
    }

    private static AppException duplicateName(String name) {
        return new AppException(ErrorCode.QUOTA_POLICY_NAME_EXISTED, "Đã có chính sách quota tên: " + name);
    }
}
