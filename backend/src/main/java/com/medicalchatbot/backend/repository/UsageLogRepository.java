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
import com.medicalchatbot.backend.repository.projection.CostUnavailableModelProjection;
import com.medicalchatbot.backend.repository.projection.UsageTotalsProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Quota and cost queries over {@code usage_logs}.
 *
 * <p>Convention for every query below: a turn that failed mid-pipeline (status {@code failed}) has
 * already spent tokens and money, so it counts towards tokens and cost but not as a request.</p>
 */
public interface UsageLogRepository extends JpaRepository<UsageLog, UUID> {

    @Query("""
            select new com.medicalchatbot.backend.repository.projection.CacheMetricsProjection(
                count(u.id),
                sum(case when u.answerSource like '%cache%' then 1 else 0 end),
                sum(u.savedTokens),
                sum(u.savedCostUsd)
            )
            from UsageLog u
            where u.createdAt >= :startDate and u.createdAt <= :endDate
            """)
    CacheMetricsProjection getCacheMetrics(
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /** Totals of one user in {@code [startInclusive, endExclusive)}. */
    default UsageTotalsProjection summarizeUsage(UUID userId, OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        TotalsRow row = summarizeUsageRow(userId, startInclusive, endExclusive);
        return new UsageTotalsProjection(row.getRequestCount(), row.getInputTokens(), row.getOutputTokens(), row.getCostUsd());
    }

    default List<CostByModelProjection> summarizeCostByModel(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        return summarizeCostByModelRows(userId, startInclusive, endExclusive).stream()
                .map(row -> new CostByModelProjection(
                        row.getLlmProvider(),
                        row.getLlmModel(),
                        row.getCallCount(),
                        row.getInputTokens(),
                        row.getOutputTokens(),
                        row.getCostUsd()
                ))
                .toList();
    }

    /** Daily totals, where a "day" is a calendar day in {@code zoneId}. */
    default List<CostByDayProjection> summarizeCostByDay(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive,
            String zoneId
    ) {
        return summarizeCostByDayRows(userId, startInclusive, endExclusive, zoneId).stream()
                .map(row -> new CostByDayProjection(
                        row.getUsageDate(),
                        row.getRequestCount(),
                        row.getInputTokens(),
                        row.getOutputTokens(),
                        row.getCostUsd()
                ))
                .toList();
    }

    /** Models with calls the gateway did not price (recorded as 0) — the period total is incomplete. */
    default List<CostUnavailableModelProjection> findCostUnavailableModels(
            UUID userId,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        return findCostUnavailableModelRows(userId, startInclusive, endExclusive).stream()
                .map(row -> new CostUnavailableModelProjection(row.getLlmProvider(), row.getLlmModel(), row.getCallCount()))
                .toList();
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
    TotalsRow summarizeUsageRow(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

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
    List<CostByModelRow> summarizeCostByModelRows(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

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
    List<CostByDayRow> summarizeCostByDayRows(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive,
            @Param("zoneId") String zoneId
    );

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
    List<CostUnavailableModelRow> findCostUnavailableModelRows(
            @Param("userId") UUID userId,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    interface TotalsRow {
        int getRequestCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getCostUsd();
    }

    interface CostByModelRow {
        String getLlmProvider();

        String getLlmModel();

        int getCallCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getCostUsd();
    }

    interface CostByDayRow {
        LocalDate getUsageDate();

        int getRequestCount();

        int getInputTokens();

        int getOutputTokens();

        BigDecimal getCostUsd();
    }

    interface CostUnavailableModelRow {
        String getLlmProvider();

        String getLlmModel();

        int getCallCount();
    }
}
