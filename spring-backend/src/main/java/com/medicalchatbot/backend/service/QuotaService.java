package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.response.QuotaPolicyInfo;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.dto.response.QuotaUsageSummary;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class QuotaService {

    private final UserRepository userRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final UsageLogRepository usageLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;
    private final ZoneId quotaZone;

    @Autowired
    public QuotaService(
            UserRepository userRepository,
            QuotaPolicyRepository quotaPolicyRepository,
            UsageLogRepository usageLogRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            AlertService alertService,
            NotificationService notificationService,
            CurrentUserService currentUserService) {
        this(
                userRepository,
                quotaPolicyRepository,
                usageLogRepository,
                auditLogRepository,
                objectMapper,
                alertService,
                notificationService,
                ZoneId.systemDefault(),
                currentUserService);
    }

    QuotaService(
            UserRepository userRepository,
            QuotaPolicyRepository quotaPolicyRepository,
            UsageLogRepository usageLogRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            AlertService alertService,
            NotificationService notificationService,
            ZoneId quotaZone,
            CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.quotaPolicyRepository = quotaPolicyRepository;
        this.usageLogRepository = usageLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.alertService = alertService;
        this.notificationService = notificationService;
        this.currentUserService = currentUserService;
        this.quotaZone = quotaZone;
    }

    QuotaService(
            UserRepository userRepository,
            QuotaPolicyRepository quotaPolicyRepository,
            UsageLogRepository usageLogRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            AlertService alertService,
            NotificationService notificationService,
            CurrentUserService currentUserService,
            ZoneId quotaZone) {
        this(
                userRepository,
                quotaPolicyRepository,
                usageLogRepository,
                auditLogRepository,
                objectMapper,
                alertService,
                notificationService,
                quotaZone,
                currentUserService);
    }

    public QuotaStatusResponse currentUserStatus() {
        User user = currentUserService.requireCurrentUser();
        return statusForUser(user.getId(), user.getUsername());
    }

    public QuotaStatusResponse getCurrentUserStatus() {
        return currentUserStatus();
    }

    public QuotaStatusResponse getUserStatusByUsername(String targetUsername) {
        UUID userId = getUserIdByUsername(targetUsername);
        return statusForUser(userId, targetUsername);
    }

    public List<QuotaPolicy> getAllQuotaPolicies() {
        return quotaPolicyRepository.findAll();
    }

    public void assertQuotaAvailable(UUID userId) {
        QuotaStatusResponse status = statusForUser(userId, null);
        if (status.allowed()) {
            return;
        }

        alertService.triggerAlert(
                "QUOTA_SYSTEM",
                "QUOTA_EXCEEDED",
                AlertSeverity.WARNING,
                "User " + userId + " bị chặn do: " + status.blockedReason(),
                objectMapper.valueToTree(status));
        logQuotaBlocked(userId, status);
        throw new QuotaExceededException(status.blockedReason(), status);
    }

    /**
     * Tỷ lệ quota đã dùng trong ngày
     */
    public double currentUsedRatio(UUID userId) {
        QuotaStatusResponse status = statusForUser(userId, null);
        double requestRatio = ratio(status.usedRequests(), status.dailyRequestLimit());
        double tokenRatio = ratio(status.usedTokens(), status.dailyTokenLimit());
        double costRatio = ratio(status.usedCostUsd(), status.dailyCostLimitUsd());
        return Math.max(requestRatio, Math.max(tokenRatio, costRatio));
    }

    @Cacheable(value = "rateLimitConfig", key = "#username")
    public int getRateLimitForUser(String username) {
        if (username == null || username.isBlank() || "anonymousUser".equals(username)) {
            return 5;
        }
        Integer configuredLimit = quotaPolicyRepository.findRateLimitByUsername(username);
        return configuredLimit != null ? configuredLimit : 5;
    }

    private QuotaStatusResponse statusForUser(UUID userId, String username) {
        QuotaPolicyInfo policy = quotaPolicyRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Không tìm thấy quota policy cho người dùng."));
        ZonedDateTime now = ZonedDateTime.now(quotaZone);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(quotaZone).toOffsetDateTime();
        OffsetDateTime startOfNextDay = startOfDay.plusDays(1);
        QuotaUsageSummary usage = usageLogRepository.summarizeSuccessfulUsage(
                userId,
                startOfDay,
                startOfNextDay);

        int usedTokens = usage.usedTokens();
        checkAndTriggerQuotaWarning(userId, policy, usage, usedTokens);

        int remainingRequests = remaining(policy.dailyRequestLimit(), usage.usedRequests());
        int remainingTokens = remaining(policy.dailyTokenLimit(), usedTokens);
        BigDecimal remainingCost = remaining(policy.dailyCostLimitUsd(), usage.usedCostUsd());
        String blockedReason = blockedReason(policy, usage, usedTokens);

        return new QuotaStatusResponse(
                username != null ? username : userId.toString(),
                policy.policyName(),
                policy.dailyRequestLimit(),
                policy.dailyTokenLimit(),
                policy.dailyCostLimitUsd(),
                usage.usedRequests(),
                usage.usedInputTokens(),
                usage.usedOutputTokens(),
                usedTokens,
                usage.usedCostUsd(),
                remainingRequests,
                remainingTokens,
                remainingCost,
                blockedReason == null,
                blockedReason);
    }

    private UUID getUserIdByUsername(String username) {
        return userRepository.findIdByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy người dùng: " + username));
    }

    private String blockedReason(QuotaPolicyInfo policy, QuotaUsageSummary usage, int usedTokens) {
        if (usage.usedRequests() >= policy.dailyRequestLimit()) {
            return "Đã vượt quá hạn mức " + policy.dailyRequestLimit() + " lượt gọi AI/ngày.";
        }
        if (usedTokens >= policy.dailyTokenLimit()) {
            return "Đã vượt quá hạn mức " + policy.dailyTokenLimit() + " token/ngày.";
        }
        if (usage.usedCostUsd().compareTo(policy.dailyCostLimitUsd()) >= 0) {
            return "Đã vượt quá hạn mức chi phí AI/ngày.";
        }
        return null;
    }

    private int remaining(int limit, int used) {
        return Math.max(0, limit - used);
    }

    private BigDecimal remaining(BigDecimal limit, BigDecimal used) {
        BigDecimal result = limit.subtract(used);
        return result.signum() < 0 ? BigDecimal.ZERO : result;
    }

    private void logQuotaBlocked(UUID userId, QuotaStatusResponse status) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "quota_check");
        metadata.put("blocked_reason", status.blockedReason());
        metadata.set("quota_status", objectMapper.valueToTree(status));

        User user = userRepository.findById(userId).orElse(null);

        auditLogRepository.save(
                user,
                null,
                "QUOTA_BLOCKED",
                "app_user",
                userId.toString(),
                metadata);
    }

    private void checkAndTriggerQuotaWarning(UUID userId, QuotaPolicyInfo policy, QuotaUsageSummary usage,
            int usedTokens) {
        double requestUsagePct = ratio(usage.usedRequests(), policy.dailyRequestLimit());
        double tokenUsagePct = ratio(usedTokens, policy.dailyTokenLimit());

        if (requestUsagePct < 0.8 && tokenUsagePct < 0.8) {
            return;
        }
        try {
            if (!notificationService.hasQuotaWarningBeenSentToday(userId)) {
                double maxPct = Math.max(requestUsagePct, tokenUsagePct);
                String content = String.format(
                        "Hạn mức sử dụng hằng ngày của bạn đã đạt %.1f%%. Vui lòng sử dụng tiết kiệm.",
                        maxPct * 100);
                notificationService.createNotification(
                        userId,
                        NotificationType.QUOTA_WARNING,
                        "Cảnh báo hạn mức sử dụng (Quota Warning)",
                        content);
            }
        } catch (Exception ex) {
            log.error("Failed to create quota warning notification for user {}", userId, ex);
        }
    }

    private double ratio(int used, int limit) {
        if (limit <= 0) {
            return 0;
        }
        return (double) used / limit;
    }

    private double ratio(BigDecimal used, BigDecimal limit) {
        if (used == null || limit == null || limit.signum() <= 0) {
            return 0;
        }
        return used.doubleValue() / limit.doubleValue();
    }
}
