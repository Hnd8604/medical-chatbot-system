package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.ModelPricingUpsertRequest;
import com.medicalchatbot.backend.dto.response.ModelPricingAdminResponse;
import com.medicalchatbot.backend.entity.ModelPricing;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ModelPricingAdminService {

    private final ModelPricingRepository modelPricingRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public ModelPricingAdminService(
            ModelPricingRepository modelPricingRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.modelPricingRepository = modelPricingRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ModelPricingAdminResponse> list() {
        return modelPricingRepository.findAll().stream()
                .map(ModelPricingAdminResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ModelPricingAdminResponse get(UUID id) {
        return ModelPricingAdminResponse.from(getEntity(id));
    }

    @Transactional
    public ModelPricingAdminResponse create(ModelPricingUpsertRequest request) {
        if (request.activeOrDefault()) {
            assertNoActiveDuplicate(request.provider(), request.model(), null);
        }
        ModelPricing pricing = new ModelPricing(
                request.provider(),
                request.model(),
                request.inputPricePer1mTokens(),
                request.outputPricePer1mTokens(),
                request.currencyOrDefault(),
                request.activeOrDefault()
        );
        ModelPricing saved = modelPricingRepository.save(pricing);
        audit("MODEL_PRICING_CREATED", saved);
        return ModelPricingAdminResponse.from(saved);
    }

    @Transactional
    public ModelPricingAdminResponse update(UUID id, ModelPricingUpsertRequest request) {
        ModelPricing pricing = getEntity(id);
        if (request.activeOrDefault()) {
            assertNoActiveDuplicate(request.provider(), request.model(), id);
        }
        pricing.applyUpdate(
                request.provider(),
                request.model(),
                request.inputPricePer1mTokens(),
                request.outputPricePer1mTokens(),
                request.currencyOrDefault(),
                request.activeOrDefault()
        );
        ModelPricing saved = modelPricingRepository.save(pricing);
        audit("MODEL_PRICING_UPDATED", saved);
        return ModelPricingAdminResponse.from(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ModelPricing pricing = getEntity(id);
        modelPricingRepository.delete(pricing);
        audit("MODEL_PRICING_DELETED", pricing);
    }

    private ModelPricing getEntity(UUID id) {
        return modelPricingRepository.findById(id)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy model pricing: " + id
                ));
    }

    /**
     * Đảm bảo bất biến: tối đa một bản ghi <b>active</b> cho mỗi (provider, model) —
   
     */
    private void assertNoActiveDuplicate(String provider, String model, UUID selfId) {
        modelPricingRepository.findByProviderIgnoreCaseAndModelIgnoreCaseAndActiveTrue(provider, model)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new AppException(
                            ErrorCode.CONFLICT,
                            "Đã tồn tại cấu hình giá active cho model: " + provider + "/" + model
                    );
                });
    }

    private void audit(String action, ModelPricing pricing) {
        try {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("provider", pricing.getProvider());
            metadata.put("model", pricing.getModel());
            metadata.put("input_price_per_1m_tokens", pricing.getInputPricePer1mTokens());
            metadata.put("output_price_per_1m_tokens", pricing.getOutputPricePer1mTokens());
            metadata.put("currency", pricing.getCurrency());
            metadata.put("active", pricing.isActive());
            auditLogRepository.save(
                    null,
                    null,
                    action,
                    "model_pricing",
                    pricing.getId() != null ? pricing.getId().toString() : null,
                    metadata
            );
        } catch (Exception ex) {
            log.error("Failed to write audit log {} for model pricing", action, ex);
        }
    }
}
