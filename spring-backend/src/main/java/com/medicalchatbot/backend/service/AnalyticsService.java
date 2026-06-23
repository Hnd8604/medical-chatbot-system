package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IntentAnalyticsResponse> getIntentAnalytics(int page, int daysPerPage) {
        LocalDate endDate = LocalDate.now().minusDays((long) page * daysPerPage);
        LocalDate startDate = endDate.minusDays(daysPerPage - 1);

        String sql = """
            SELECT CAST(DATE(created_at) AS VARCHAR) as date,
                   action as intent,
                   COUNT(*) as count
            FROM audit_logs
            WHERE action != 'CHAT_COMPLETED'
              AND DATE(created_at) >= ? AND DATE(created_at) <= ?
            GROUP BY DATE(created_at), action
            ORDER BY date DESC, count DESC
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new IntentAnalyticsResponse(
                rs.getString("date"),
                rs.getString("intent"),
                rs.getLong("count")
        ), startDate, endDate);
    }

    public List<ErrorAnalyticsResponse> getErrorAnalytics(int page, int daysPerPage) {
        LocalDate endDate = LocalDate.now().minusDays((long) page * daysPerPage);
        LocalDate startDate = endDate.minusDays(daysPerPage - 1);

        String sql = """
            SELECT CAST(DATE(created_at) AS VARCHAR) as date,
                   source as service,
                   alert_type as errorType,
                   COUNT(*) as count
            FROM alerts
            WHERE DATE(created_at) >= ? AND DATE(created_at) <= ?
            GROUP BY DATE(created_at), source, alert_type
            ORDER BY date DESC, count DESC
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new ErrorAnalyticsResponse(
                rs.getString("date"),
                rs.getString("service"),
                rs.getString("errorType"),
                rs.getLong("count")
        ), startDate, endDate);
    }

    public List<PerformanceAnalyticsResponse> getPerformanceAnalytics() {
        String sql = """
            SELECT llm_model as model,
                   AVG(latency_ms) as avgLatency,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) as p95Latency,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) as p99Latency
            FROM usage_logs
            WHERE status = 'success' AND latency_ms IS NOT NULL
            GROUP BY llm_model
            ORDER BY avgLatency DESC
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new PerformanceAnalyticsResponse(
                rs.getString("model"),
                rs.getDouble("avgLatency"),
                rs.getDouble("p95Latency"),
                rs.getDouble("p99Latency")
        ));
    }
}
