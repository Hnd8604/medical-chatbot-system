package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CostManagementService {

    private final UsageLogRepository usageLogRepository;
    private final ModelPricingRepository modelPricingRepository;
    private final ZoneId costZone;
    private final CurrentUserService currentUserService;

    @Autowired
    public CostManagementService(
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            CurrentUserService currentUserService
    ) {
        this(usageLogRepository, modelPricingRepository, ZoneId.systemDefault(), currentUserService);
    }

    CostManagementService(
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            ZoneId costZone,
            CurrentUserService currentUserService
    ) {
        this.usageLogRepository = usageLogRepository;
        this.modelPricingRepository = modelPricingRepository;
        this.costZone = costZone;
        this.currentUserService = currentUserService;
    }

    public CostSummaryResponse currentUserCostSummary(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Khoảng thời gian thống kê chi phí không hợp lệ."
            );
        }

        UUID userId = currentUserService.requireCurrentUserId();
        OffsetDateTime startInclusive = from.atStartOfDay(costZone).toOffsetDateTime();
        OffsetDateTime endExclusive = to.plusDays(1).atStartOfDay(costZone).toOffsetDateTime();
        CostSummaryResponse totals = usageLogRepository.summarizeCost(userId, startInclusive, endExclusive);

        return new CostSummaryResponse(
                from,
                to,
                totals.requestCount(),
                totals.inputTokens(),
                totals.outputTokens(),
                totals.totalTokens(),
                totals.estimatedCostUsd(),
                usageLogRepository.summarizeCostByModel(userId, startInclusive, endExclusive),
                usageLogRepository.summarizeCostByDay(userId, startInclusive, endExclusive, costZone.getId()),
                usageLogRepository.findMissingPricingModels(userId, startInclusive, endExclusive)
        );
    }

    public ModelPricingListResponse activePricing() {
        return new ModelPricingListResponse(modelPricingRepository.findActivePricing());
    }
}
