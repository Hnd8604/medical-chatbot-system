package com.medicalchatbot.backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "model_pricing")
public class ModelPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_price_per_1m_tokens", nullable = false, precision = 12, scale = 6)
    private BigDecimal inputPricePer1mTokens;

    @Column(name = "output_price_per_1m_tokens", nullable = false, precision = 12, scale = 6)
    private BigDecimal outputPricePer1mTokens;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ModelPricing() {
    }

    public ModelPricing(
            String provider,
            String model,
            BigDecimal inputPricePer1mTokens,
            BigDecimal outputPricePer1mTokens,
            String currency,
            boolean active
    ) {
        this.provider = provider;
        this.model = model;
        this.inputPricePer1mTokens = inputPricePer1mTokens;
        this.outputPricePer1mTokens = outputPricePer1mTokens;
        this.currency = currency;
        this.active = active;
    }

    
    public void applyUpdate(
            String provider,
            String model,
            BigDecimal inputPricePer1mTokens,
            BigDecimal outputPricePer1mTokens,
            String currency,
            boolean active
    ) {
        this.provider = provider;
        this.model = model;
        this.inputPricePer1mTokens = inputPricePer1mTokens;
        this.outputPricePer1mTokens = outputPricePer1mTokens;
        this.currency = currency;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public BigDecimal getInputPricePer1mTokens() {
        return inputPricePer1mTokens;
    }

    public BigDecimal getOutputPricePer1mTokens() {
        return outputPricePer1mTokens;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
