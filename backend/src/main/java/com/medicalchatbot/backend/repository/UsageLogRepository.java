package com.medicalchatbot.backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.repository.projection.CacheMetricsProjection;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostSummaryProjection;
import com.medicalchatbot.backend.repository.projection.CostUnavailableModelProjection;
import com.medicalchatbot.backend.repository.projection.QuotaUsageProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageLogRepository extends JpaRepository<UsageLog, UUID> {

    @Query("""
        SELECT new com.medicalchatbot.backend.repository.projection.CacheMetricsProjection(
            COUNT(u.id),
            SUM(CASE WHEN u.answerSource LIKE '%cache%' THEN 1 ELSE 0 END),
            SUM(u.savedTokens),
            SUM(u.savedCostUsd)
        )
        FROM UsageLog u
        WHERE u.createdAt >= :startDate AND u.createdAt <= :endDate
    """)
    CacheMetricsProjection getCacheObservabilityMetrics(
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /*
     * Quy ước cho mọi truy vấn quota/chi phí bên dưới: lượt hỏng giữa pipeline (status
     * 'failed') vẫn đã tốn token/tiền nên được cộng vào token và cost, nhưng không tính là
     * một lượt gọi AI (request) của người dùng.
     */

    @Query(
            value = """
                    select
                        coalesce(sum(request_count) filter (where status = 'success'), 0)::int as "usedRequests",
                        coalesce(sum(input_tokens), 0)::int as "usedInputTokens",
                        coalesce(sum(output_tokens), 0)::int as "usedOutputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "usedCostUsd"
                    from usage_logs
                    where user_id = :userId
                      and status in ('success', 'failed')
                      and created_at >= :startInclusive
                      and created_at < :endExclusive
                    """,
            nativeQuery = true
    )
    QuotaUsageSummaryView summarizeUsageView(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    default QuotaUsageProjection summarizeUsage(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        QuotaUsageSummaryView summary = summarizeUsageView(userId, startInclusive, endExclusive);
        return new QuotaUsageProjection(
                summary.getUsedRequests(),
                summary.getUsedInputTokens(),
                summary.getUsedOutputTokens(),
                summary.getUsedCostUsd()
        );
    }

    @Query(
            value = """
                    select
                        coalesce(sum(request_count) filter (where status = 'success'), 0)::int as "requestCount",
                        coalesce(sum(input_tokens), 0)::int as "inputTokens",
                        coalesce(sum(output_tokens), 0)::int as "outputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "costUsd"
                    from usage_logs
                    where user_id = :userId
                      and status in ('success', 'failed')
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

    default CostSummaryProjection summarizeCost(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        CostSummaryView summary = summarizeCostView(userId, startInclusive, endExclusive);
        return new CostSummaryProjection(
                summary.getRequestCount(),
                summary.getInputTokens(),
                summary.getOutputTokens(),
                summary.getCostUsd()
        );
    }

    @Query(
            value = """
                    select
                        llm_provider as "llmProvider",
                        llm_model as "llmModel",
                        coalesce(sum(call_count), 0)::int as "callCount",
                        coalesce(sum(input_tokens), 0)::int as "inputTokens",
                        coalesce(sum(output_tokens), 0)::int as "outputTokens",
                        coalesce(sum(cost_usd), 0)::numeric(12, 6) as "costUsd"
                    from (
                        -- Mỗi LLM call một dòng. Một lượt chat gọi nhiều call nên đây là số call,
                        -- không so được với request_count (số lượt chat) của tổng kỳ.
                        select
                            coalesce(lc.llm_provider, 'unknown') as llm_provider,
                            coalesce(lc.llm_model, 'unknown') as llm_model,
                            1 as call_count,
                            lc.input_tokens,
                            lc.output_tokens,
                            lc.cost_usd
                        from llm_call_usage lc
                        join usage_logs usage on usage.id = lc.usage_log_id
                        where usage.user_id = :userId
                          and usage.status in ('success', 'failed')
                          and usage.created_at >= :startInclusive
                          and usage.created_at < :endExclusive
                        union all
                        -- Dòng cũ (trước V10, cost ước tính) chưa có chi tiết call: gán cả lượt cho model
                        -- chính, mỗi lượt tính là một call.
                        select
                            coalesce(usage.llm_provider, 'unknown'),
                            coalesce(usage.llm_model, 'unknown'),
                            usage.request_count,
                            usage.input_tokens,
                            usage.output_tokens,
                            usage.estimated_cost_usd
                        from usage_logs usage
                        where usage.user_id = :userId
                          and usage.status = 'success'
                          and usage.cost_source <> 'none'
                          and usage.created_at >= :startInclusive
                          and usage.created_at < :endExclusive
                          and not exists (select 1 from llm_call_usage lc where lc.usage_log_id = usage.id)
                    ) usage_by_model
                    group by llm_provider, llm_model
                    order by "costUsd" desc, "callCount" desc, "llmProvider", "llmModel"
                    """,
            nativeQuery = true
    )
    List<CostByModelView> summarizeCostByModelViews(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    default List<CostByModelProjection> summarizeCostByModel(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        return summarizeCostByModelViews(userId, startInclusive, endExclusive)
                .stream()
                .map(model -> new CostByModelProjection(
                        model.getLlmProvider(),
                        model.getLlmModel(),
                        model.getCallCount(),
                        model.getInputTokens(),
                        model.getOutputTokens(),
                        model.getCostUsd()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        usage_date as "usageDate",
                        coalesce(sum(request_count) filter (where status = 'success'), 0)::int as "requestCount",
                        coalesce(sum(input_tokens), 0)::int as "inputTokens",
                        coalesce(sum(output_tokens), 0)::int as "outputTokens",
                        coalesce(sum(estimated_cost_usd), 0)::numeric(12, 6) as "costUsd"
                    from (
                        select
                            cast(created_at at time zone :zoneId as date) as usage_date,
                            status,
                            request_count,
                            input_tokens,
                            output_tokens,
                            estimated_cost_usd
                        from usage_logs
                        where user_id = :userId
                          and status in ('success', 'failed')
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

    default List<CostByDayProjection> summarizeCostByDay(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive,
            String zoneId
    ) {
        return summarizeCostByDayViews(userId, startInclusive, endExclusive, zoneId)
                .stream()
                .map(day -> new CostByDayProjection(
                        day.getUsageDate(),
                        day.getRequestCount(),
                        day.getInputTokens(),
                        day.getOutputTokens(),
                        day.getCostUsd()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        coalesce(lc.llm_provider, 'unknown') as "llmProvider",
                        coalesce(lc.llm_model, 'unknown') as "llmModel",
                        count(*)::int as "callCount"
                    from llm_call_usage lc
                    join usage_logs usage on usage.id = lc.usage_log_id
                    where usage.user_id = :userId
                      and usage.status in ('success', 'failed')
                      and usage.created_at >= :startInclusive
                      and usage.created_at < :endExclusive
                      and lc.cost_source = 'unavailable'
                    group by coalesce(lc.llm_provider, 'unknown'), coalesce(lc.llm_model, 'unknown')
                    order by "callCount" desc, "llmProvider", "llmModel"
                    """,
            nativeQuery = true
    )
    List<CostUnavailableModelView> findCostUnavailableModelViews(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    /** Model có call mà gateway không báo cost (ghi 0) — tổng chi phí kỳ đó bị thiếu. */
    default List<CostUnavailableModelProjection> findCostUnavailableModels(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        return findCostUnavailableModelViews(userId, startInclusive, endExclusive)
                .stream()
                .map(model -> new CostUnavailableModelProjection(
                        model.getLlmProvider(),
                        model.getLlmModel(),
                        model.getCallCount()
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

        BigDecimal getCostUsd();
    }

    interface CostByModelView {
        String getLlmProvider();

        String getLlmModel();

        int getCallCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getCostUsd();
    }

    interface CostByDayView {
        LocalDate getUsageDate();

        int getRequestCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getCostUsd();
    }

    interface CostUnavailableModelView {
        String getLlmProvider();

        String getLlmModel();

        int getCallCount();
    }
}
