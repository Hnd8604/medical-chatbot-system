package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.QuotaPolicyInfo;
import com.medicalchatbot.backend.dto.response.QuotaUsageSummary;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private AuditLogRepository auditLogRepository;

    @Mock
    private AlertService alertService;

    @Mock
    private NotificationService notificationService;

    @Test
    void demoUserStatusReturnsRemainingQuota() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        QuotaService service = newService();

        when(userRepository.findIdByUsername("demo_user")).thenReturn(Optional.of(userId));
        when(quotaPolicyRepository.findByUserId(userId)).thenReturn(Optional.of(new QuotaPolicyInfo(
                "free_demo",
                50,
                1000,
                new BigDecimal("1.00")
        )));
        when(usageLogRepository.summarizeSuccessfulUsage(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(new QuotaUsageSummary(
                12,
                100,
                50,
                new BigDecimal("0.25")
        ));

        var status = service.demoUserStatus();

        assertEquals("demo_user", status.user());
        assertEquals("free_demo", status.policy());
        assertEquals(12, status.usedRequests());
        assertEquals(150, status.usedTokens());
        assertEquals(38, status.remainingRequests());
        assertEquals(850, status.remainingTokens());
        assertEquals(new BigDecimal("0.75"), status.remainingCostUsd());
        assertEquals(true, status.allowed());
    }

    @Test
    void assertQuotaAvailableBlocksAndAuditsWhenDailyRequestLimitReached() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        QuotaService service = newService();

        when(quotaPolicyRepository.findByUserId(userId)).thenReturn(Optional.of(new QuotaPolicyInfo(
                "free_demo",
                1,
                1000,
                new BigDecimal("1.00")
        )));
        when(usageLogRepository.summarizeSuccessfulUsage(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(new QuotaUsageSummary(
                1,
                0,
                0,
                BigDecimal.ZERO
        ));
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User(userId)));

        QuotaExceededException exception = assertThrows(
                QuotaExceededException.class,
                () -> service.assertQuotaAvailable(userId)
        );

        assertEquals(false, exception.quotaStatus().allowed());
        assertEquals(0, exception.quotaStatus().remainingRequests());
        verify(auditLogRepository).save(
                any(User.class),
                isNull(),
                eq("QUOTA_BLOCKED"),
                eq("app_user"),
                eq(userId.toString()),
                any()
        );

        verify(alertService).triggerAlert(
                eq("QUOTA_SYSTEM"),
                eq("QUOTA_EXCEEDED"),
                eq(AlertSeverity.WARNING),
                anyString(),
                any()
        );
    }

    @Test
    void assertQuotaAvailableBlocksWhenDailyCostLimitReached() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        QuotaService service = newService();

        when(quotaPolicyRepository.findByUserId(userId)).thenReturn(Optional.of(new QuotaPolicyInfo(
                "free_demo",
                50,
                100000,
                new BigDecimal("0.01")
        )));
        when(usageLogRepository.summarizeSuccessfulUsage(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(new QuotaUsageSummary(
                1,
                100,
                50,
                new BigDecimal("0.01")
        ));
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User(userId)));

        QuotaExceededException exception = assertThrows(
                QuotaExceededException.class,
                () -> service.assertQuotaAvailable(userId)
        );

        assertEquals(false, exception.quotaStatus().allowed());
        assertEquals(0, BigDecimal.ZERO.compareTo(exception.quotaStatus().remainingCostUsd()));
        assertEquals(
                "\u0110\u00e3 v\u01b0\u1ee3t qu\u00e1 h\u1ea1n m\u1ee9c chi ph\u00ed AI/ng\u00e0y.",
                exception.getMessage()
        );
    }

    private QuotaService newService() {
        return new QuotaService(
                userRepository,
                quotaPolicyRepository,
                usageLogRepository,
                auditLogRepository,
                new ObjectMapper(),
                alertService,
                notificationService,
                ZoneId.of("Asia/Saigon")
        );
    }
}
