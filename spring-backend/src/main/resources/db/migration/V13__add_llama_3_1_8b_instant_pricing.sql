UPDATE model_pricing
SET active = false,
    updated_at = now()
WHERE LOWER(provider) = 'groq'
  AND LOWER(model) = 'llama-3.1-8b-instant'
  AND id <> '00000000-0000-0000-0000-000000001301';

INSERT INTO model_pricing (
    id,
    provider,
    model,
    input_price_per_1m_tokens,
    output_price_per_1m_tokens,
    currency,
    active
)
VALUES (
    '00000000-0000-0000-0000-000000001301',
    'groq',
    'llama-3.1-8b-instant',
    0.050000,
    0.080000,
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
  AND pricing.active = true
  AND LOWER(pricing.provider) = 'groq'
  AND LOWER(pricing.model) = 'llama-3.1-8b-instant';
