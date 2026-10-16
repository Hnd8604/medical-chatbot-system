package com.medicalchatbot.backend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.dto.response.usage.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import com.medicalchatbot.backend.service.CostService;
import com.medicalchatbot.backend.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UsageController.class)
@AutoConfigureMockMvc(addFilters = false)
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private CostService costService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void quotaReturnsTodaysUsage() throws Exception {
        when(quotaService.currentUserStatus()).thenReturn(new QuotaStatusResponse("demo_user", "user_standard",
                50, 100000, new BigDecimal("1.00"), 12, 100, 50, 150, new BigDecimal("0.10"), 38, 99850,
                new BigDecimal("0.90"), true, null));

        mockMvc.perform(get("/usage/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.user").value("demo_user"))
                .andExpect(jsonPath("$.result.dailyRequestLimit").value(50))
                .andExpect(jsonPath("$.result.remainingRequests").value(38))
                .andExpect(jsonPath("$.result.allowed").value(true));
    }

    @Test
    void costsReturnTheSummaryWithNestedBreakdowns() throws Exception {
        LocalDate day = LocalDate.parse("2026-06-01");
        when(costService.currentUserSummary(day, day)).thenReturn(new CostSummaryResponse(day, day, 2, 3000, 700, 3700,
                new BigDecimal("0.002320"),
                List.of(new CostSummaryResponse.ModelCost("openai", "gpt-4.1-mini", 2, 3000, 700, 3700, new BigDecimal("0.002320"))),
                List.of(new CostSummaryResponse.DailyCost(day, 2, 3000, 700, 3700, new BigDecimal("0.002320"))),
                List.of()));

        mockMvc.perform(get("/usage/costs?from=2026-06-01&to=2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.from").value("2026-06-01"))
                .andExpect(jsonPath("$.result.totalTokens").value(3700))
                .andExpect(jsonPath("$.result.models[0].llmModel").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.result.days[0].requestCount").value(2))
                .andExpect(jsonPath("$.result.costUnavailableModels").isEmpty());
    }

    @Test
    void costsRequireTheDateRange() throws Exception {
        mockMvc.perform(get("/usage/costs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Thiếu tham số bắt buộc: from"));
    }
}
