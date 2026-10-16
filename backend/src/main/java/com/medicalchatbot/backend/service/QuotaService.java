package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.projection.QuotaLimitsProjection;
import com.medicalchatbot.backend.repository.projection.UsageTotalsProjection;
import com.medicalchatbot.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Daily AI quotas (requests, tokens, cost) per user, computed from {@code usage_logs} over the
 * current calendar day of the application time zone. Spring is the only place limits are enforced:
 * the LLM gateway does not track spend per user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    /** Users are warned (once a day) when any daily usage reaches this share of its limit. */
    private static final double WARNING_RATIO = 0.8;
    /** Chat requests per minute for a policy without its own rate limit. */
    private static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 5;

    private final UserRepository userRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final UsageLogRepository usageLogRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final AlertService alertService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public QuotaStatusResponse currentUserStatus() {
        AuthenticatedUser principal = currentUserService.requirePrincipal();
        return status(principal.id(), principal.username());
    }

    public QuotaStatusResponse userStatus(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng."));
        return status(user.getId(), user.getUsername());
    }

    /**
     * Rejects the request with HTTP 429 when a daily limit is used up.
     *
     * @return the status just computed, so the caller does not compute it a second time
     */
    public QuotaStatusResponse assertQuotaAvailable(User user) {
        QuotaStatusResponse status = status(user.getId(), user.getUsername());
        if (status.allowed()) {
            return status;
        }

        JsonNode statusJson = objectMapper.valueToTree(status);
        alertService.raise(AlertType.QUOTA_EXCEEDED,
                "Người dùng " + user.getUsername() + " bị chặn do: " + status.blockedReason(), statusJson);
        auditService.recordIndependently(user, AuditAction.QUOTA_BLOCKED, AuditResource.APP_USER, user.getId().toString(),
                auditService.metadata()
                        .put("operation", "quota_check")
                        .put("blocked_reason", status.blockedReason())
                        .set("quota_status", statusJson));
        throw new QuotaExceededException(status);
    }

    /** Highest share (0..1+) of any daily limit already used; chatbot-service picks cheaper models near 1. */
    public double usedRatio(QuotaStatusResponse status) {
        return Math.max(
                ratio(status.usedRequests(), status.dailyRequestLimit()),
                Math.max(
                        ratio(status.usedTokens(), status.dailyTokenLimit()),
                        ratio(status.usedCostUsd(), status.dailyCostLimitUsd())
                )
        );
    }

    public int rateLimitPerMinute(UUID userId) {
        return quotaPolicyRepository.findRateLimitPerMinuteByUserId(userId).orElse(DEFAULT_RATE_LIMIT_PER_MINUTE);
    }

    private QuotaStatusResponse status(UUID userId, String username) {
        QuotaLimitsProjection limits = quotaPolicyRepository.findLimitsByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                        "Không tìm thấy quota policy cho người dùng."));
        ZoneId zone = appProperties.timezone();
        OffsetDateTime startOfDay = OffsetDateTime.now(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
        UsageTotalsProjection usage = usageLogRepository.summarizeUsage(userId, startOfDay, startOfDay.plusDays(1));

        warnIfNearLimit(userId, limits, usage);
        String blockedReason = blockedReason(limits, usage);
        return new QuotaStatusResponse(
                username,
                limits.policyName(),
                limits.dailyRequestLimit(),
                limits.dailyTokenLimit(),
                limits.dailyCostLimitUsd(),
                usage.requestCount(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.costUsd(),
                Math.max(0, limits.dailyRequestLimit() - usage.requestCount()),
                Math.max(0, limits.dailyTokenLimit() - usage.totalTokens()),
                limits.dailyCostLimitUsd().subtract(usage.costUsd()).max(BigDecimal.ZERO),
                blockedReason == null,
                blockedReason
        );
    }

    private static String blockedReason(QuotaLimitsProjection limits, UsageTotalsProjection usage) {
        if (usage.requestCount() >= limits.dailyRequestLimit()) {
            return "Đã vượt quá hạn mức " + limits.dailyRequestLimit() + " lượt gọi AI/ngày.";
        }
        if (usage.totalTokens() >= limits.dailyTokenLimit()) {
            return "Đã vượt quá hạn mức " + limits.dailyTokenLimit() + " token/ngày.";
        }
        if (usage.costUsd().compareTo(limits.dailyCostLimitUsd()) >= 0) {
            return "Đã vượt quá hạn mức chi phí AI/ngày.";
        }
        return null;
    }

    /** Best effort: a failing notification must not block the quota check. */
    private void warnIfNearLimit(UUID userId, QuotaLimitsProjection limits, UsageTotalsProjection usage) {
        double usedRatio = Math.max(
                ratio(usage.requestCount(), limits.dailyRequestLimit()),
                Math.max(
                        ratio(usage.totalTokens(), limits.dailyTokenLimit()),
                        ratio(usage.costUsd(), limits.dailyCostLimitUsd())
                )
        );
        if (usedRatio < WARNING_RATIO) {
            return;
        }
        try {
            if (!notificationService.quotaWarningSentToday(userId)) {
                notificationService.notify(
                        userId,
                        NotificationType.QUOTA_WARNING,
                        "Cảnh báo hạn mức sử dụng",
                        String.format("Hạn mức sử dụng hằng ngày của bạn đã đạt %.1f%%. Vui lòng sử dụng tiết kiệm.",
                                usedRatio * 100)
                );
            }
        } catch (RuntimeException exception) {
            log.error("Failed to create quota warning notification for user {}", userId, exception);
        }
    }

    private static double ratio(int used, int limit) {
        return limit <= 0 ? 0 : (double) used / limit;
    }

    private static double ratio(BigDecimal used, BigDecimal limit) {
        if (used == null || limit == null || limit.signum() <= 0) {
            return 0;
        }
        return used.doubleValue() / limit.doubleValue();
    }
}
