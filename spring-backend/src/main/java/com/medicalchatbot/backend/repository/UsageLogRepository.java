package com.medicalchatbot.backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.*;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageLogRepository extends JpaRepository<UsageLog, UUID> {
    default void save(
            User user,
            ChatSession session,
            String llmProvider,
            String llmModel,
            String operation,
            String status,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            BigDecimal estimatedCostUsd,
            String errorMessage
    ) {
        save(
                user,
                session,
                llmProvider,
                llmModel,
                operation,
                status,
                latencyMs,
                inputTokens,
                outputTokens,
                estimatedCostUsd,
                errorMessage,
                null,
                0,
                BigDecimal.ZERO
        );
    }


    default void save(
            User user,
            ChatSession session,
            String llmProvider,
            String llmModel,
            String operation,
            String status,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            BigDecimal estimatedCostUsd,
            String errorMessage,
            String answerSource,
            int savedTokens,
            BigDecimal savedCostUsd
    ) {
        save(new UsageLog(
                user,
                session,
                llmProvider,
                llmModel,
                operation,
                status,
                latencyMs,
                inputTokens,
                outputTokens,
                estimatedCostUsd,
                errorMessage,
                answerSource,
                savedTokens,
                savedCostUsd
        ));
    }

    @Query("""
        SELECT new com.medicalchatbot.backend.dto.response.CacheMetricsResponse(
            COUNT(u.id),
            SUM(CASE WHEN u.answerSource LIKE '%cache%' THEN 1 ELSE 0 END),
            SUM(u.savedTokens),
            SUM(u.savedCostUsd)
        )
        FROM UsageLog u
        WHERE u.createdAt >= :startDate AND u.createdAt <= :endDate
    """)
    CacheMetricsResponse getCacheObservabilityMetrics(
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    @Query(
            value = """
                    select
                        coalesce(sum(request_count), 0)::int as "usedRequests",
                        coalesce(sum(input_tokens), 0)::int as "usedInputTokens",
                        coalesce(sum(output_tokens), 0)::int as "usedOutputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "usedCostUsd"
                    from usage_logs
                    where user_id = :userId
                      and status = 'success'
                      and created_at >= :startInclusive
                      and created_at < :endExclusive
                    """,
            nativeQuery = true
    )
    QuotaUsageSummaryView summarizeSuccessfulUsageView(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    default QuotaUsageSummary summarizeSuccessfulUsage(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        QuotaUsageSummaryView summary = summarizeSuccessfulUsageView(userId, startInclusive, endExclusive);
        return new QuotaUsageSummary(
                summary.getUsedRequests(),
                summary.getUsedInputTokens(),
                summary.getUsedOutputTokens(),
                summary.getUsedCostUsd()
        );
    }

    @Query(
            value = """
                    select
                        coalesce(sum(request_count), 0)::int as "requestCount",
                        coalesce(sum(input_tokens), 0)::int as "inputTokens",
                        coalesce(sum(output_tokens), 0)::int as "outputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "estimatedCostUsd"
                    from usage_logs
                    where user_id = :userId
                      and status = 'success'
                      and created_at >= :startInclusive
                      and created_at < :endExclusive
                    """,
            nativeQuery = true
    )
    CostSummaryView summarizeCostView(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    default CostSummaryResponse summarizeCost(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        CostSummaryView summary = summarizeCostView(userId, startInclusive, endExclusive);
        int inputTokens = summary.getInputTokens();
        int outputTokens = summary.getOutputTokens();
        return new CostSummaryResponse(
                null,
                null,
                summary.getRequestCount(),
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                summary.getEstimatedCostUsd(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    @Query(
            value = """
                    select
                        coalesce(llm_provider, 'unknown') as "llmProvider",
                        coalesce(llm_model, 'unknown') as "llmModel",
                        coalesce(sum(request_count), 0)::int as "requestCount",
                        coalesce(sum(input_tokens), 0)::int as "inputTokens",
                        coalesce(sum(output_tokens), 0)::int as "outputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "estimatedCostUsd"
                    from usage_logs
                    where user_id = :userId
                      and status = 'success'
                      and created_at >= :startInclusive
                      and created_at < :endExclusive
                    group by coalesce(llm_provider, 'unknown'), coalesce(llm_model, 'unknown')
                    order by "estimatedCostUsd" desc, "requestCount" desc, "llmProvider", "llmModel"
                    """,
            nativeQuery = true
    )
    List<CostByModelView> summarizeCostByModelViews(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    default List<CostByModel> summarizeCostByModel(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        return summarizeCostByModelViews(userId, startInclusive, endExclusive)
                .stream()
                .map(model -> new CostByModel(
                        model.getLlmProvider(),
                        model.getLlmModel(),
                        model.getRequestCount(),
                        model.getInputTokens(),
                        model.getOutputTokens(),
                        model.getInputTokens() + model.getOutputTokens(),
                        model.getEstimatedCostUsd()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        usage_date as "usageDate",
                        coalesce(sum(request_count), 0)::int as "requestCount",
                        coalesce(sum(input_tokens), 0)::int as "inputTokens",
                        coalesce(sum(output_tokens), 0)::int as "outputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "estimatedCostUsd"
                    from (
                        select
                            cast(created_at at time zone :zoneId as date) as usage_date,
                            request_count,
                            input_tokens,
                            output_tokens,
                            estimated_cost_usd
                        from usage_logs
                        where user_id = :userId
                          and status = 'success'
                          and created_at >= :startInclusive
                          and created_at < :endExclusive
                    ) usage_by_day
                    group by usage_date
                    order by usage_date
                    """,
            nativeQuery = true
    )
    List<CostByDayView> summarizeCostByDayViews(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive,
            @Param("zoneId") String zoneId
    );

    default List<CostByDay> summarizeCostByDay(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive,
            String zoneId
    ) {
        return summarizeCostByDayViews(userId, startInclusive, endExclusive, zoneId)
                .stream()
                .map(day -> new CostByDay(
                        day.getUsageDate(),
                        day.getRequestCount(),
                        day.getInputTokens(),
                        day.getOutputTokens(),
                        day.getInputTokens() + day.getOutputTokens(),
                        day.getEstimatedCostUsd()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        usage.llm_provider as "llmProvider",
                        usage.llm_model as "llmModel",
                        coalesce(sum(usage.request_count), 0)::int as "requestCount"
                    from usage_logs usage
                    left join model_pricing pricing
                      on lower(pricing.provider) = lower(usage.llm_provider)
                     and lower(pricing.model) = lower(usage.llm_model)
                     and pricing.active = true
                    where usage.user_id = :userId
                      and usage.status = 'success'
                      and usage.created_at >= :startInclusive
                      and usage.created_at < :endExclusive
                      and usage.llm_provider is not null
                      and usage.llm_model is not null
                      and pricing.id is null
                    group by usage.llm_provider, usage.llm_model
                    order by "requestCount" desc, "llmProvider", "llmModel"
                    """,
            nativeQuery = true
    )
    List<MissingPricingModelView> findMissingPricingModelViews(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    default List<MissingPricingModel> findMissingPricingModels(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        return findMissingPricingModelViews(userId, startInclusive, endExclusive)
                .stream()
                .map(model -> new MissingPricingModel(
                        model.getLlmProvider(),
                        model.getLlmModel(),
                        model.getRequestCount()
                ))
                .toList();
    }

    interface QuotaUsageSummaryView {
        int getUsedRequests();

        int getUsedInputTokens();

        int getUsedOutputTokens();

        BigDecimal getUsedCostUsd();
    }

    interface CostSummaryView {
        int getRequestCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getEstimatedCostUsd();
    }

    interface CostByModelView {
        String getLlmProvider();

        String getLlmModel();

        int getRequestCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getEstimatedCostUsd();
    }

    interface CostByDayView {
        LocalDate getUsageDate();

        int getRequestCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getEstimatedCostUsd();
    }

    interface MissingPricingModelView {
        String getLlmProvider();

        String getLlmModel();

        int getRequestCount();
    }
}
