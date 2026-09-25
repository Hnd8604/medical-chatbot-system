package com.medicalchatbot.backend.repository.jdbc;

import com.medicalchatbot.backend.repository.AnalyticsQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcAnalyticsQueryRepositoryTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 7);

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcAnalyticsQueryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcAnalyticsQueryRepository(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findIntentStatsMapsJdbcRow() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("date")).thenReturn("2026-09-02");
        when(resultSet.getString("intent")).thenReturn("medication");
        when(resultSet.getLong("count")).thenReturn(8L);
        doAnswer(invocation -> {
            RowMapper<AnalyticsQueryRepository.IntentStat> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AnalyticsQueryRepository.IntentStat>>any(),
                eq(FROM),
                eq(TO),
                eq(10)
        );

        List<AnalyticsQueryRepository.IntentStat> result = repository.findIntentStats(FROM, TO, 10);

        assertEquals(List.of(new AnalyticsQueryRepository.IntentStat(
                "2026-09-02",
                "medication",
                8L
        )), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findErrorStatsMapsJdbcRow() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("date")).thenReturn("2026-09-03");
        when(resultSet.getString("service")).thenReturn("chatbot-service");
        when(resultSet.getString("errorType")).thenReturn("TIMEOUT");
        when(resultSet.getLong("count")).thenReturn(2L);
        doAnswer(invocation -> {
            RowMapper<AnalyticsQueryRepository.ErrorStat> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AnalyticsQueryRepository.ErrorStat>>any(),
                eq(FROM),
                eq(TO),
                eq(5)
        );

        List<AnalyticsQueryRepository.ErrorStat> result = repository.findErrorStats(FROM, TO, 5);

        assertEquals(List.of(new AnalyticsQueryRepository.ErrorStat(
                "2026-09-03",
                "chatbot-service",
                "TIMEOUT",
                2L
        )), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findPerformanceStatsMapsJdbcRow() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("model")).thenReturn("gpt-4.1-mini");
        when(resultSet.getDouble("avgLatency")).thenReturn(320.5);
        when(resultSet.getDouble("p95Latency")).thenReturn(550.0);
        when(resultSet.getDouble("p99Latency")).thenReturn(700.0);
        doAnswer(invocation -> {
            RowMapper<AnalyticsQueryRepository.PerformanceStat> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AnalyticsQueryRepository.PerformanceStat>>any(),
                eq(FROM),
                eq(TO),
                eq(3)
        );

        List<AnalyticsQueryRepository.PerformanceStat> result = repository.findPerformanceStats(FROM, TO, 3);

        assertEquals(List.of(new AnalyticsQueryRepository.PerformanceStat(
                "gpt-4.1-mini",
                320.5,
                550.0,
                700.0
        )), result);
    }

    @Test
    void countChatRequestsReturnsJdbcResult() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(FROM), eq(TO)))
                .thenReturn(23L);

        assertEquals(23L, repository.countChatRequests(FROM, TO));

        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), eq(FROM), eq(TO));
    }

    @Test
    void countChatRequestsFallsBackToZeroForNullJdbcResult() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(FROM), eq(TO)))
                .thenReturn(null);

        assertEquals(0L, repository.countChatRequests(FROM, TO));
    }
}
