package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.CostMapper;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.projection.CostSummaryProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CostManagementService {

    private final UserRepository userRepository;
    private final UsageLogRepository usageLogRepository;
    private final ModelPricingRepository modelPricingRepository;
    private final CurrentUserService currentUserService;
    private final ZoneId costZone;
    private final CostMapper costMapper;

    @Autowired
    public CostManagementService(
            UserRepository userRepository,
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            CurrentUserService currentUserService,
            CostMapper costMapper
    ) {
        this(
                userRepository,
                usageLogRepository,
                modelPricingRepository,
                currentUserService,
                ZoneId.systemDefault(),
                costMapper
        );
    }

    CostManagementService(
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            ZoneId costZone,
            CurrentUserService currentUserService
    ) {
        this(null, usageLogRepository, modelPricingRepository, currentUserService, costZone, new CostMapper());
    }

    CostManagementService(
            UserRepository userRepository,
            UsageLogRepository usageLogRepository,
            ModelPricingRepository modelPricingRepository,
            CurrentUserService currentUserService,
            ZoneId costZone,
            CostMapper costMapper
    ) {
        this.userRepository = userRepository;
        this.usageLogRepository = usageLogRepository;
        this.modelPricingRepository = modelPricingRepository;
        this.currentUserService = currentUserService;
        this.costZone = costZone;
        this.costMapper = costMapper;
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
        return new ModelPricingListResponse(costMapper.toPricingInfo(modelPricingRepository.findActivePricing()));
    }

    private CostSummaryResponse costSummaryForUser(UUID userId, LocalDate from, LocalDate to) {
        OffsetDateTime startInclusive = from.atStartOfDay(costZone).toOffsetDateTime();
        OffsetDateTime endExclusive = to.plusDays(1).atStartOfDay(costZone).toOffsetDateTime();
        CostSummaryProjection totals = usageLogRepository.summarizeCost(userId, startInclusive, endExclusive);

        return costMapper.toCostSummary(
                from,
                to,
                totals,
                usageLogRepository.summarizeCostByModel(userId, startInclusive, endExclusive),
                usageLogRepository.summarizeCostByDay(userId, startInclusive, endExclusive, costZone.getId()),
                usageLogRepository.findMissingPricingModels(userId, startInclusive, endExclusive)
        );
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new AppException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Khoảng thời gian thống kê chi phí không hợp lệ."
            );
        }
    }

    private UUID getUserIdByUsername(String username) {
        if (userRepository == null) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "User repository chưa được cấu hình.");
        }
        return userRepository.findIdByUsername(username)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy người dùng: " + username
                ));
    }
}
