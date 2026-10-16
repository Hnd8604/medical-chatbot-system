package com.medicalchatbot.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.repository.projection.ErrorStat;
import com.medicalchatbot.backend.repository.projection.IntentStat;
import com.medicalchatbot.backend.repository.projection.PerformanceStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class AnalyticsRepositoryTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 7);

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AnalyticsRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AnalyticsRepository(jdbcTemplate);
    }

    @Test
    void findIntentStatsMapsJdbcRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("date")).thenReturn("2026-09-02");
        when(row.getString("intent")).thenReturn("medication");
        when(row.getLong("count")).thenReturn(8L);
        answerWithRow(row, 10);

        assertEquals(List.of(new IntentStat("2026-09-02", "medication", 8L)), repository.findIntentStats(FROM, TO, 10));
    }

    @Test
    void findErrorStatsMapsJdbcRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("date")).thenReturn("2026-09-03");
        when(row.getString("service")).thenReturn("chatbot-service");
        when(row.getString("error_type")).thenReturn("TIMEOUT");
        when(row.getLong("count")).thenReturn(2L);
        answerWithRow(row, 5);

        assertEquals(List.of(new ErrorStat("2026-09-03", "chatbot-service", "TIMEOUT", 2L)),
                repository.findErrorStats(FROM, TO, 5));
    }

    @Test
    void findPerformanceStatsMapsJdbcRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("model")).thenReturn("gpt-4.1-mini");
        when(row.getDouble("avg_latency")).thenReturn(320.5);
        when(row.getDouble("p95_latency")).thenReturn(550.0);
        when(row.getDouble("p99_latency")).thenReturn(700.0);
        answerWithRow(row, 3);

        assertEquals(List.of(new PerformanceStat("gpt-4.1-mini", 320.5, 550.0, 700.0)),
                repository.findPerformanceStats(FROM, TO, 3));
    }

    @Test
    void countChatRequestsReturnsJdbcResult() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(FROM), eq(TO))).thenReturn(23L);

        assertEquals(23L, repository.countChatRequests(FROM, TO));
    }

    @Test
    void countChatRequestsFallsBackToZeroForNullJdbcResult() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(FROM), eq(TO))).thenReturn(null);

        assertEquals(0L, repository.countChatRequests(FROM, TO));
    }

    /** Makes the JdbcTemplate run the repository's row mapper over a single row. */
    private void answerWithRow(ResultSet row, int limit) {
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbcTemplate).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), eq(FROM), eq(TO), eq(limit));
    }
}
