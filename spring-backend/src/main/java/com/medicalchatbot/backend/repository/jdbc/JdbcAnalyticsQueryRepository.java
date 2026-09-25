package com.medicalchatbot.backend.repository.jdbc;

import com.medicalchatbot.backend.repository.AnalyticsQueryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class JdbcAnalyticsQueryRepository implements AnalyticsQueryRepository {

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
                   n.error_type AS errorType,
                   COUNT(*) AS count
            FROM normalized n
            JOIN top_errors t
              ON t.service = n.service AND t.error_type = n.error_type
            GROUP BY n.date, n.service, n.error_type, t.total_count
            ORDER BY t.total_count DESC, n.service ASC, n.error_type ASC, n.date DESC
            """;

    private static final String PERFORMANCE_STATS_SQL = """
            SELECT llm_model as model,
                   AVG(latency_ms) as avgLatency,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) as p95Latency,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) as p99Latency
            FROM usage_logs
            WHERE status = 'success'
              AND latency_ms IS NOT NULL
              AND DATE(created_at) >= ? AND DATE(created_at) <= ?
            GROUP BY llm_model
            ORDER BY avgLatency DESC
            LIMIT ?
            """;

    private static final String REQUEST_COUNT_SQL = """
            SELECT COALESCE(SUM(request_count), 0) AS request_count
            FROM usage_logs
            WHERE operation = 'chat'
              AND DATE(created_at) >= ? AND DATE(created_at) <= ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAnalyticsQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<IntentStat> findIntentStats(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(INTENT_STATS_SQL, (rs, rowNum) -> new IntentStat(
                rs.getString("date"),
                rs.getString("intent"),
                rs.getLong("count")
        ), from, to, limit);
    }

    @Override
    public List<ErrorStat> findErrorStats(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(ERROR_STATS_SQL, (rs, rowNum) -> new ErrorStat(
                rs.getString("date"),
                rs.getString("service"),
                rs.getString("errorType"),
                rs.getLong("count")
        ), from, to, limit);
    }

    @Override
    public List<PerformanceStat> findPerformanceStats(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(PERFORMANCE_STATS_SQL, (rs, rowNum) -> new PerformanceStat(
                rs.getString("model"),
                rs.getDouble("avgLatency"),
                rs.getDouble("p95Latency"),
                rs.getDouble("p99Latency")
        ), from, to, limit);
    }

    @Override
    public long countChatRequests(LocalDate from, LocalDate to) {
        Long count = jdbcTemplate.queryForObject(REQUEST_COUNT_SQL, Long.class, from, to);
        return count == null ? 0L : count;
    }
}
