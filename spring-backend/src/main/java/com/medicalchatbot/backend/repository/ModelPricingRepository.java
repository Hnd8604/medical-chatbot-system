package com.medicalchatbot.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.ModelPricing;
import com.medicalchatbot.backend.repository.projection.ModelPricingProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, UUID> {

    @Query("""
            select new com.medicalchatbot.backend.repository.projection.ModelPricingProjection(
                p.provider,
                p.model,
                p.inputPricePer1mTokens,
                p.outputPricePer1mTokens,
                p.currency
            )
            from ModelPricing p
            where lower(p.provider) = lower(:provider)
              and lower(p.model) = lower(:model)
              and p.active = true
            """)
    Optional<ModelPricingProjection> findActivePricing(
            @Param("provider") String provider,
            @Param("model") String model
    );

    default Optional<ModelPricingProjection> findActiveByProviderAndModel(String provider, String model) {
        if (provider == null || model == null || provider.isBlank() || model.isBlank()) {
            return Optional.empty();
        }
        return findActivePricing(provider, model);
    }

    @Query("""
            select new com.medicalchatbot.backend.repository.projection.ModelPricingProjection(
                p.provider,
                p.model,
                p.inputPricePer1mTokens,
                p.outputPricePer1mTokens,
                p.currency
            )
            from ModelPricing p
            where p.active = true
            order by p.provider, p.model
            """)
    List<ModelPricingProjection> findActivePricing();

    /**
     * Tìm bản ghi pricing đang active cho (provider, model). Partial unique index
     * {@code idx_model_pricing_active_provider_model} (V5) đảm bảo tối đa 1 bản ghi.
     */
    Optional<ModelPricing> findByProviderIgnoreCaseAndModelIgnoreCaseAndActiveTrue(String provider, String model);
}
