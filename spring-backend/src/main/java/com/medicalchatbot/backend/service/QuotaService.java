package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.response.QuotaPolicyInfo;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.dto.response.QuotaUsageSummary;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuotaService {

    private static final String DEMO_USERNAME = "demo_user";

    private final UserRepository userRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final UsageLogRepository usageLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ZoneId quotaZone;
    private final NotificationService notificationService;

    @Autowired
    public QuotaService(
            UserRepository userRepository,
            QuotaPolicyRepository quotaPolicyRepository,
            UsageLogRepository usageLogRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            NotificationService notificationService
    ) {
        this(
                userRepository,
                quotaPolicyRepository,
                usageLogRepository,
                auditLogRepository,
                objectMapper,
                notificationService,
                ZoneId.systemDefault()
        );
    }

    QuotaService(
            UserRepository userRepository,
            QuotaPolicyRepository quotaPolicyRepository,
            UsageLogRepository usageLogRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            NotificationService notificationService,
            ZoneId quotaZone
    ) {
        this.userRepository = userRepository;
        this.quotaPolicyRepository = quotaPolicyRepository;
        this.usageLogRepository = usageLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.quotaZone = quotaZone;
    }

    public QuotaStatusResponse demoUserStatus() {
        UUID userId = getDemoUserId();
        return statusForUser(userId, DEMO_USERNAME);
    }

    public void assertQuotaAvailable(UUID userId) {
        QuotaStatusResponse status = statusForUser(userId, null);
        if (status.allowed()) {
            return;
        }

        logQuotaBlocked(userId, status);
        throw new QuotaExceededException(status.blockedReason(), status);
    }

    @Cacheable(value = "rateLimitConfig", key = "#userId")
    public int getRateLimitForUser(String username) {
        if ("anonymousUser".equals(username)) {
            return 5;
        }
        return quotaPolicyRepository.findRateLimitByUsername(username);

    }

    private QuotaStatusResponse statusForUser(UUID userId, String username) {
        QuotaPolicyInfo policy = quotaPolicyRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Kh\u00f4ng t\u00ecm th\u1ea5y quota policy cho ng\u01b0\u1eddi d\u00f9ng."
                ));
        ZonedDateTime now = ZonedDateTime.now(quotaZone);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(quotaZone).toOffsetDateTime();
        OffsetDateTime startOfNextDay = startOfDay.plusDays(1);
        QuotaUsageSummary usage = usageLogRepository.summarizeSuccessfulUsage(
                userId,
                startOfDay,
                startOfNextDay
        );

        int usedTokens = usage.usedTokens();
        
        // M25.1 Trigger quota warning if >= 80% used
        checkAndTriggerQuotaWarning(userId, policy, usage, usedTokens);

        int remainingRequests = remaining(policy.dailyRequestLimit(), usage.usedRequests());
        int remainingTokens = remaining(policy.dailyTokenLimit(), usedTokens);
        BigDecimal remainingCost = remaining(policy.dailyCostLimitUsd(), usage.usedCostUsd());
        String blockedReason = blockedReason(policy, usage, usedTokens);

        return new QuotaStatusResponse(
                username,
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
                blockedReason
        );
    }

    private UUID getDemoUserId() {
        return userRepository.findIdByUsername(DEMO_USERNAME)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Kh\u00f4ng t\u00ecm th\u1ea5y ng\u01b0\u1eddi d\u00f9ng demo."
                ));
    }

    private String blockedReason(QuotaPolicyInfo policy, QuotaUsageSummary usage, int usedTokens) {
        if (usage.usedRequests() >= policy.dailyRequestLimit()) {
            return "\u0110\u00e3 v\u01b0\u1ee3t qu\u00e1 h\u1ea1n m\u1ee9c " + policy.dailyRequestLimit() + " l\u01b0\u1ee3t g\u1ecdi AI/ng\u00e0y.";
        }
        if (usedTokens >= policy.dailyTokenLimit()) {
            return "\u0110\u00e3 v\u01b0\u1ee3t qu\u00e1 h\u1ea1n m\u1ee9c " + policy.dailyTokenLimit() + " token/ng\u00e0y.";
        }
        if (usage.usedCostUsd().compareTo(policy.dailyCostLimitUsd()) >= 0) {
            return "\u0110\u00e3 v\u01b0\u1ee3t qu\u00e1 h\u1ea1n m\u1ee9c chi ph\u00ed AI/ng\u00e0y.";
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
                metadata
        );
    }

    private void checkAndTriggerQuotaWarning(UUID userId, QuotaPolicyInfo policy, QuotaUsageSummary usage, int usedTokens) {
        double requestUsagePct = (double) usage.usedRequests() / policy.dailyRequestLimit();
        double tokenUsagePct = (double) usedTokens / policy.dailyTokenLimit();

        if (requestUsagePct >= 0.8 || tokenUsagePct >= 0.8) {
            if (!notificationService.hasQuotaWarningBeenSentToday(userId)) {
                double maxPct = Math.max(requestUsagePct, tokenUsagePct);
                String content = String.format("Hạn mức sử dụng hằng ngày của bạn đã đạt %.1f%%. Vui lòng sử dụng tiết kiệm.", maxPct * 100);
                notificationService.createNotification(
                        userId,
                        com.medicalchatbot.backend.enums.NotificationType.QUOTA_WARNING,
                        "Cảnh báo hạn mức sử dụng (Quota Warning)",
                        content
                );
            }
        }
    }
}
