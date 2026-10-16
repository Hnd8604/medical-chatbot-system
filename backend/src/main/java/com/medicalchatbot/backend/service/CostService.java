package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.dto.response.usage.CostSummaryResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.CostMapper;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.SecurityUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** AI usage and cost reports. Costs come from the LLM gateway; Spring never estimates them. */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CostService {

    UsageLogRepository usageLogRepository;
    UserRepository userRepository;
    CostMapper costMapper;
    AppProperties appProperties;

    public CostSummaryResponse currentUserSummary(LocalDate from, LocalDate to) {
        return summary(SecurityUtils.currentUserId(), from, to);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CostSummaryResponse userSummary(UUID userId, LocalDate from, LocalDate to) {
        if (!userRepository.existsById(userId)) {
            throw new AppException(ErrorCode.USER_NOT_EXISTED);
        }
        return summary(userId, from, to);
    }

    /** Totals over the calendar days {@code [from, to]} of the application time zone. */
    private CostSummaryResponse summary(UUID userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new AppException(ErrorCode.DATE_RANGE_INVALID);
        }
        ZoneId zone = appProperties.timezone();
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        return costMapper.toCostSummary(
                from,
                to,
                usageLogRepository.summarizeUsage(userId, start, end),
                usageLogRepository.summarizeCostByModel(userId, start, end),
                usageLogRepository.summarizeCostByDay(userId, start, end, zone.getId()),
                usageLogRepository.findCostUnavailableModels(userId, start, end)
        );
    }
}
