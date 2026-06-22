package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CostManagementService {

    private final UserRepository userRepository;
    private final UsageLogRepository usageLogRepository;
    private final ModelPricingRepository modelPricingRepository;
    private final CurrentUserService currentUserService;
    private final ZoneId costZone;

    @Autowired
    public CostManagementService(
            UserRepository userRepository,
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            CurrentUserService currentUserService
    ) {
        this(userRepository, usageLogRepository, modelPricingRepository, currentUserService, ZoneId.systemDefault());
    }

    CostManagementService(
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            ZoneId costZone,
            CurrentUserService currentUserService
    ) {
        this(null, usageLogRepository, modelPricingRepository, currentUserService, costZone);
    }

    CostManagementService(
            UserRepository userRepository,
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            CurrentUserService currentUserService,
            ZoneId costZone
    ) {
        this.userRepository = userRepository;
        this.usageLogRepository = usageLogRepository;
        this.modelPricingRepository = modelPricingRepository;
        this.currentUserService = currentUserService;
        this.costZone = costZone;
    }

    public CostSummaryResponse currentUserCostSummary(LocalDate from, LocalDate to) {
        validateRange(from, to);
        UUID userId = currentUserService.requireCurrentUserId();
        return costSummaryForUser(userId, from, to);
    }

    public CostSummaryResponse getCurrentUserCostSummary(LocalDate from, LocalDate to) {
        return currentUserCostSummary(from, to);
    }

    public CostSummaryResponse getUserCostSummaryByUsername(String username, LocalDate from, LocalDate to) {
        validateRange(from, to);
        UUID userId = getUserIdByUsername(username);
        return costSummaryForUser(userId, from, to);
    }

    public ModelPricingListResponse activePricing() {
        return new ModelPricingListResponse(modelPricingRepository.findActivePricing());
    }

    private CostSummaryResponse costSummaryForUser(UUID userId, LocalDate from, LocalDate to) {
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

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Khoảng thời gian thống kê chi phí không hợp lệ."
            );
        }
    }

    private UUID getUserIdByUsername(String username) {
        if (userRepository == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "User repository chưa được cấu hình.");
        }
        return userRepository.findIdByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy người dùng: " + username
                ));
    }
}
