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

    // --- API DÀNH CHO USER ĐANG ĐĂNG NHẬP ---
    public CostSummaryResponse getCurrentUserCostSummary(LocalDate from, LocalDate to) {
        String username = currentUserService.getCurrentUsername();
        UUID userId = getUserIdByUsername(username);
        return getCostSummaryForUser(userId, from, to);
    }

    // --- API DÀNH CHO ADMIN/MANAGER ---
    public CostSummaryResponse getUserCostSummaryByUsername(String username, LocalDate from, LocalDate to) {
        UUID userId = getUserIdByUsername(username);
        return getCostSummaryForUser(userId, from, to);
    }

    // --- CORE LOGIC TÍNH TOÁN ---
    private CostSummaryResponse getCostSummaryForUser(UUID userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Kho\u1ea3ng th\u1eddi gian th\u1ed1ng k\u00ea chi ph\u00ed kh\u00f4ng h\u1ee3p l\u1ec7."
            );
        }

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

    private UUID getUserIdByUsername(String username) {
        return userRepository.findIdByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Kh\u00f4ng t\u00ecm th\u1ea5y ng\u01b0\u1eddi d\u00f9ng: " + username
                ));
    }
}