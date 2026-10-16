package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.mapper.CostMapper;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** AI usage and cost reports. Costs come from the LLM gateway; Spring never estimates them. */
@Service
@RequiredArgsConstructor
public class CostService {

    private final UsageLogRepository usageLogRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final CostMapper costMapper;
    private final AppProperties appProperties;

    public CostSummaryResponse currentUserSummary(LocalDate from, LocalDate to) {
        return summary(currentUserService.requireCurrentUserId(), from, to);
    }

    public CostSummaryResponse userSummary(UUID userId, LocalDate from, LocalDate to) {
        if (!userRepository.existsById(userId)) {
            throw AppException.notFound("Không tìm thấy người dùng.");
        }
        return summary(userId, from, to);
    }

    /** Totals over the calendar days {@code [from, to]} of the application time zone. */
    private CostSummaryResponse summary(UUID userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw AppException.invalid("Khoảng thời gian thống kê chi phí không hợp lệ.");
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
