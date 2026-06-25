package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_RANGE_DAYS = 90;
    private static final int MAX_LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IntentAnalyticsResponse> getIntentAnalytics(LocalDate from, LocalDate to, int limit) {
        DateRange range = normalizeRange(from, to);
        int safeLimit = normalizeLimit(limit);

        String sql = """
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

        return jdbcTemplate.query(sql, (rs, rowNum) -> new IntentAnalyticsResponse(
                rs.getString("date"),
                rs.getString("intent"),
                rs.getLong("count")
        ), range.from(), range.to(), safeLimit);
    }

    public List<ErrorAnalyticsResponse> getErrorAnalytics(LocalDate from, LocalDate to, int limit) {
        DateRange range = normalizeRange(from, to);
        int safeLimit = normalizeLimit(limit);

        String sql = """
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

        return jdbcTemplate.query(sql, (rs, rowNum) -> new ErrorAnalyticsResponse(
                rs.getString("date"),
                rs.getString("service"),
                rs.getString("errorType"),
                rs.getLong("count")
        ), range.from(), range.to(), safeLimit);
    }

    public List<PerformanceAnalyticsResponse> getPerformanceAnalytics(LocalDate from, LocalDate to, int limit) {
        DateRange range = normalizeRange(from, to);
        int safeLimit = normalizeLimit(limit);

        String sql = """
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

        return jdbcTemplate.query(sql, (rs, rowNum) -> new PerformanceAnalyticsResponse(
                rs.getString("model"),
                rs.getDouble("avgLatency"),
                rs.getDouble("p95Latency"),
                rs.getDouble("p99Latency")
        ), range.from(), range.to(), safeLimit);
    }

    private DateRange normalizeRange(LocalDate from, LocalDate to) {
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(DEFAULT_DAYS - 1L);
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to.");
        }
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Analytics range cannot exceed 90 days.");
        }
        return new DateRange(startDate, endDate);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
