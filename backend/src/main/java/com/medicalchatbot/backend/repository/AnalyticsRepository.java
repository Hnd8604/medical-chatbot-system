package com.medicalchatbot.backend.repository;

import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.repository.projection.ErrorStat;
import com.medicalchatbot.backend.repository.projection.IntentStat;
import com.medicalchatbot.backend.repository.projection.PerformanceStat;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only aggregate queries for the admin analytics screens. */
@Repository
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AnalyticsRepository {

    /** Top intents in the window, broken down per day; reads the intent recorded on chat audit entries. */
    private static final String INTENT_STATS_SQL = """
            WITH normalized AS (
                SELECT DATE(created_at) AS date,
                       COALESCE(
                           NULLIF(metadata_json ->> 'question_intent', ''),
                           NULLIF(metadata_json ->> 'intent', '')
                       ) AS intent
                FROM audit_logs
                WHERE metadata_json ->> 'operation' = 'chat'
                  AND DATE(created_at) >= ? AND DATE(created_at) <= ?
            ),
            top_intents AS (
                SELECT intent, COUNT(*) AS total_count
                FROM normalized
                WHERE intent IS NOT NULL
                GROUP BY intent
                ORDER BY total_count DESC, intent ASC
                LIMIT ?
            )
            SELECT CAST(n.date AS VARCHAR) AS date,
                   n.intent AS intent,
                   COUNT(*) AS count
            FROM normalized n
            JOIN top_intents t ON t.intent = n.intent
            GROUP BY n.date, n.intent, t.total_count
            ORDER BY t.total_count DESC, n.intent ASC, n.date DESC
            """;

    /** Top (source, alert type) pairs in the window, broken down per day. */
    private static final String ERROR_STATS_SQL = """
            WITH normalized AS (
                SELECT DATE(created_at) AS date,
                       COALESCE(NULLIF(source, ''), 'unknown') AS service,
                       COALESCE(NULLIF(alert_type, ''), 'SYSTEM') AS error_type
                FROM alerts
                WHERE DATE(created_at) >= ? AND DATE(created_at) <= ?
            ),
            top_errors AS (
                SELECT service, error_type, COUNT(*) AS total_count
                FROM normalized
                GROUP BY service, error_type
                ORDER BY total_count DESC, service ASC, error_type ASC
                LIMIT ?
            )
            SELECT CAST(n.date AS VARCHAR) AS date,
                   n.service AS service,
                   n.error_type AS error_type,
                   COUNT(*) AS count
            FROM normalized n
            JOIN top_errors t
              ON t.service = n.service AND t.error_type = n.error_type
            GROUP BY n.date, n.service, n.error_type, t.total_count
            ORDER BY t.total_count DESC, n.service ASC, n.error_type ASC, n.date DESC
            """;

    private static final String PERFORMANCE_STATS_SQL = """
            SELECT llm_model AS model,
                   AVG(latency_ms) AS avg_latency,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95_latency,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) AS p99_latency
            FROM usage_logs
            WHERE status = 'success'
              AND latency_ms IS NOT NULL
              AND DATE(created_at) >= ? AND DATE(created_at) <= ?
            GROUP BY llm_model
            ORDER BY avg_latency DESC
            LIMIT ?
            """;

    private static final String REQUEST_COUNT_SQL = """
            SELECT COALESCE(SUM(request_count), 0) AS request_count
            FROM usage_logs
            WHERE operation = 'chat'
              AND DATE(created_at) >= ? AND DATE(created_at) <= ?
            """;

    JdbcTemplate jdbcTemplate;

    public List<IntentStat> findIntentStats(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(INTENT_STATS_SQL, (rs, rowNum) -> new IntentStat(
                rs.getString("date"),
                rs.getString("intent"),
                rs.getLong("count")
        ), from, to, limit);
    }

    public List<ErrorStat> findErrorStats(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(ERROR_STATS_SQL, (rs, rowNum) -> new ErrorStat(
                rs.getString("date"),
                rs.getString("service"),
                rs.getString("error_type"),
                rs.getLong("count")
        ), from, to, limit);
    }

    public List<PerformanceStat> findPerformanceStats(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(PERFORMANCE_STATS_SQL, (rs, rowNum) -> new PerformanceStat(
                rs.getString("model"),
                rs.getDouble("avg_latency"),
                rs.getDouble("p95_latency"),
                rs.getDouble("p99_latency")
        ), from, to, limit);
    }

    public long countChatRequests(LocalDate from, LocalDate to) {
        Long count = jdbcTemplate.queryForObject(REQUEST_COUNT_SQL, Long.class, from, to);
        return count == null ? 0L : count;
    }
}
