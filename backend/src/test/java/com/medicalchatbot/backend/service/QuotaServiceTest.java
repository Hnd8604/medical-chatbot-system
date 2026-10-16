package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.projection.QuotaLimitsProjection;
import com.medicalchatbot.backend.repository.projection.UsageTotalsProjection;
import com.medicalchatbot.backend.security.AuthenticatedUser;
import com.medicalchatbot.backend.support.TestProperties;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private QuotaPolicyRepository quotaPolicyRepository;

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AlertService alertService;

    @Mock
    private NotificationService notificationService;

    private QuotaService service;
    private User user;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new QuotaService(
                userRepository,
                quotaPolicyRepository,
                usageLogRepository,
                currentUserService,
                new AuditService(auditLogRepository, new AuditLogMapper(), objectMapper),
                alertService,
                notificationService,
                TestProperties.app(),
                objectMapper
        );
        user = TestUsers.user(UserRole.USER);
    }

    @Test
    void currentUserStatusReturnsRemainingQuota() {
        when(currentUserService.requirePrincipal())
                .thenReturn(new AuthenticatedUser(user.getId(), "user_demo", UserRole.USER));
        givenPolicyAndUsage(new QuotaLimitsProjection("user_standard", 30, 1000, new BigDecimal("0.50")),
                new UsageTotalsProjection(12, 100, 50, new BigDecimal("0.25")));

        QuotaStatusResponse status = service.currentUserStatus();

        assertEquals("user_demo", status.user());
        assertEquals("user_standard", status.policy());
        assertEquals(150, status.usedTokens());
        assertEquals(18, status.remainingRequests());
        assertEquals(850, status.remainingTokens());
        assertEquals(new BigDecimal("0.25"), status.remainingCostUsd());
        assertTrue(status.allowed());
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void reachedRequestLimitBlocksAlertsAndAudits() {
        givenPolicyAndUsage(new QuotaLimitsProjection("user_standard", 1, 1000, new BigDecimal("0.50")),
                new UsageTotalsProjection(1, 0, 0, BigDecimal.ZERO));

        QuotaExceededException exception = assertThrows(QuotaExceededException.class,
                () -> service.assertQuotaAvailable(user));

        assertFalse(exception.getQuotaStatus().allowed());
        assertEquals(0, exception.getQuotaStatus().remainingRequests());
        verify(alertService).raise(eq(AlertType.QUOTA_EXCEEDED), contains(user.getUsername()), any());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("QUOTA_BLOCKED", audit.getValue().getAction());
        assertEquals(user.getId().toString(), audit.getValue().getResourceId());
    }

    @Test
    void reachedCostLimitBlocksWithItsReason() {
        givenPolicyAndUsage(new QuotaLimitsProjection("user_standard", 30, 50000, new BigDecimal("0.01")),
                new UsageTotalsProjection(1, 100, 50, new BigDecimal("0.01")));

        QuotaExceededException exception = assertThrows(QuotaExceededException.class,
                () -> service.assertQuotaAvailable(user));

        assertEquals(0, BigDecimal.ZERO.compareTo(exception.getQuotaStatus().remainingCostUsd()));
        assertEquals("Đã vượt quá hạn mức chi phí AI/ngày.", exception.getMessage());
    }

    @Test
    void warnsOnceWhenAnyUsageReachesEightyPercent() {
        // Requests and tokens are low; only the cost is at 85%.
        givenPolicyAndUsage(new QuotaLimitsProjection("user_standard", 30, 50000, new BigDecimal("1.00")),
                new UsageTotalsProjection(1, 100, 50, new BigDecimal("0.85")));
        when(notificationService.quotaWarningSentToday(user.getId())).thenReturn(false);

        QuotaStatusResponse status = service.assertQuotaAvailable(user);

        assertTrue(status.allowed());
        verify(notificationService).notify(eq(user.getId()), eq(NotificationType.QUOTA_WARNING), anyString(), contains("85"));
    }

    @Test
    void usedRatioIsTheHighestShareOfAnyLimit() {
        givenPolicyAndUsage(new QuotaLimitsProjection("p", 10, 1000, new BigDecimal("1.00")),
                new UsageTotalsProjection(2, 600, 0, new BigDecimal("0.10")));

        assertEquals(0.6, service.usedRatio(service.assertQuotaAvailable(user)), 1e-9);
    }

    @Test
    void rateLimitFallsBackToTheDefault() {
        when(quotaPolicyRepository.findRateLimitPerMinuteByUserId(user.getId())).thenReturn(Optional.empty());

        assertEquals(5, service.rateLimitPerMinute(user.getId()));
    }

    private void givenPolicyAndUsage(QuotaLimitsProjection limits, UsageTotalsProjection usage) {
        when(quotaPolicyRepository.findLimitsByUserId(user.getId())).thenReturn(Optional.of(limits));
        when(usageLogRepository.summarizeUsage(eq(user.getId()), any(), any())).thenReturn(usage);
    }
}
