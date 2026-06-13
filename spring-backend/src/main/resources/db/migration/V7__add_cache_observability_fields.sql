ALTER TABLE usage_logs
    ADD COLUMN answer_source VARCHAR(50) DEFAULT 'llm';

ALTER TABLE usage_logs
    ADD COLUMN saved_tokens INT DEFAULT 0;

ALTER TABLE usage_logs
    ADD COLUMN saved_cost_usd DECIMAL(10, 6) DEFAULT 0.0;


INSERT INTO model_pricing (
    id,
    provider,
    model,
    input_price_per_1m_tokens,
    output_price_per_1m_tokens,
    currency,
    active
)
VALUES
    (
        '00000000-0000-0000-0000-000000000801',
        'groq',
        'llama-3.3-70b-versatile',
        0.590000,
        0.790000,
        'USD',
        true
    )
    ON CONFLICT (id) DO UPDATE SET
    provider = EXCLUDED.provider,
                            model = EXCLUDED.model,
                            input_price_per_1m_tokens = EXCLUDED.input_price_per_1m_tokens,
                            output_price_per_1m_tokens = EXCLUDED.output_price_per_1m_tokens,
                            currency = EXCLUDED.currency,
                            active = EXCLUDED.active,
                            updated_at = now();


UPDATE usage_logs usage
SET estimated_cost_usd = ROUND(
    (
    (usage.input_tokens::numeric * pricing.input_price_per_1m_tokens)
    + (usage.output_tokens::numeric * pricing.output_price_per_1m_tokens)
    ) / 1000000,
    6
    )
FROM model_pricing pricing
WHERE usage.estimated_cost_usd = 0
  AND usage.llm_provider IS NOT NULL
  AND usage.llm_model IS NOT NULL
  AND LOWER(pricing.provider) = LOWER(usage.llm_provider)
  AND LOWER(pricing.model) = LOWER(usage.llm_model)
  AND pricing.active = true;