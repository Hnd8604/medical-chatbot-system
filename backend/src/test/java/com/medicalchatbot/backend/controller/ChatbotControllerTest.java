package com.medicalchatbot.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatMessagesResponse;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionListResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.dto.response.CostByDay;
import com.medicalchatbot.backend.dto.response.CostByModel;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.JsonPayload;
import com.medicalchatbot.backend.dto.response.MissingPricingModel;
import com.medicalchatbot.backend.dto.response.ModelPricingInfo;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.config.JwtAuthenticationFilter;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.service.ChatApplicationService;
import com.medicalchatbot.backend.service.ChatbotQueryService;
import com.medicalchatbot.backend.service.CostManagementService;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.FeedbackService;
import com.medicalchatbot.backend.service.QuotaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatbotController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatbotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatbotQueryService chatbotQueryService;

    @MockitoBean
    private ChatApplicationService chatApplicationService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private CostManagementService costManagementService;

    @MockitoBean
    private FeedbackService feedbackService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void patientReturnsChatbotServicePayload() throws Exception {
        when(chatbotQueryService.patient("BN2026-00001"))
                .thenReturn(new JsonPayload<>(Map.of(
                        "id", "BN2026-00001",
                        "name", "Van A Nguyen"
                )));

        mockMvc.perform(get("/api/patients/BN2026-00001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value("BN2026-00001"))
                .andExpect(jsonPath("$.result.name").value("Van A Nguyen"));
    }

    @Test
    void searchPatientsReturnsChatbotServicePayload() throws Exception {
        when(chatbotQueryService.searchPatients("Nguyen Van A", null, "2003-01-01", null, 20))
                .thenReturn(new JsonPayload<>(Map.of(
                        "criteria", Map.of(
                                "name", "Nguyen Van A",
                                "birth_date", "2003-01-01"
                        ),
                        "patients", List.of(Map.of(
                                "id", "BN2026-00001",
                                "name", "Van A Nguyen"
                        ))
                )));

        mockMvc.perform(get("/api/patients?name=Nguyen Van A&birth_date=2003-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.patients[0].id").value("BN2026-00001"))
                .andExpect(jsonPath("$.result.criteria.name").value("Nguyen Van A"));
    }

    @Test
    void observationsRejectInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/patients/BN2026-00001/observations?limit=100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9009))
                .andExpect(jsonPath("$.detail").value("Dữ liệu yêu cầu không hợp lệ."));
    }

    @Test
    void encountersReturnChatbotServicePayload() throws Exception {
        when(chatbotQueryService.encounters("BN2026-00005", 5))
                .thenReturn(new JsonPayload<>(Map.of(
                        "patient_id", "BN2026-00005",
                        "encounters", List.of(Map.of(
                                "id", "ENC-2026-00006",
                                "status", "finished"
                        ))
                )));

        mockMvc.perform(get("/api/patients/BN2026-00005/encounters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.patient_id").value("BN2026-00005"))
                .andExpect(jsonPath("$.result.encounters[0].id").value("ENC-2026-00006"));
    }

    @Test
    void chatReturnsPersistedSessionResponse() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        when(chatApplicationService.chat(new ChatRequest(null, "BN2026-00001", "Bệnh nhân 001 đang dùng thuốc gì?")))
                .thenReturn(new ChatResponse(
                        sessionId,
                        null,
                        "Theo dữ liệu FHIR hiện có...",
                        "medications",
                        "get_medication_requests",
                        "llm",
                        "llm",
                        null,
                        "BN2026-00001",
                        null,
                        null,
                        objectMapper.readTree("null"),
                        null,
                        objectMapper.readTree("[]"),
                        null,
                        objectMapper.readTree("[]"),
                        objectMapper.readTree("""
                {
                  "input_tokens": 0,
                  "output_tokens": 0,
                  "estimated_cost_usd": 0
                }
                """),
                        objectMapper.readTree("""
                {
                  "input_tokens": 0,
                  "output_tokens": 0,
                  "estimated_cost_usd": 0
                }
                """),
                        objectMapper.readTree("""
                {
                  "saved_input_tokens": 0,
                  "saved_output_tokens": 0
                }
                """)
                ));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patient_id": "BN2026-00001",
                                  "message": "Bệnh nhân 001 đang dùng thuốc gì?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.session_id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.intent").value("medications"))
                .andExpect(jsonPath("$.result.tool_name").value("get_medication_requests"))
                .andExpect(jsonPath("$.result.intent_source").value("llm"))
                .andExpect(jsonPath("$.result.answer_source").value("llm"))
                .andExpect(jsonPath("$.result.patient_id").value("BN2026-00001"));
    }

    @Test
    void chatReturnsTooManyRequestsWhenQuotaExceeded() throws Exception {
        QuotaStatusResponse quotaStatus = new QuotaStatusResponse(
                "demo_user",
                "user_standard",
                1,
                100000,
                BigDecimal.ONE,
                1,
                0,
                0,
                0,
                BigDecimal.ZERO,
                0,
                100000,
                BigDecimal.ONE,
                false,
                "Đã vượt quá hạn mức 1 lượt gọi AI/ngày."
        );
        when(chatApplicationService.chat(any(ChatRequest.class)))
                .thenThrow(new QuotaExceededException(quotaStatus.blockedReason(), quotaStatus));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "danh sach benh nhan"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.detail").value("Đã vượt quá hạn mức 1 lượt gọi AI/ngày."));
    }

    @Test
    void chatSessionsReturnRecentSessions() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        when(chatApplicationService.sessions(null, 20))
                .thenReturn(new ChatSessionListResponse(List.of(new ChatSessionSummary(
                        sessionId,
                        "Thuốc của bệnh nhân 001",
                        OffsetDateTime.parse("2026-05-31T10:00:00Z"),
                        OffsetDateTime.parse("2026-05-31T10:05:00Z"),
                        "BN2026-00001",
                        2,
                        "Theo dữ liệu FHIR..."
                ))));

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sessions[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.sessions[0].title").value("Thuốc của bệnh nhân 001"))
                .andExpect(jsonPath("$.result.sessions[0].active_patient_id").value("BN2026-00001"))
                .andExpect(jsonPath("$.result.sessions[0].message_count").value(2))
                .andExpect(jsonPath("$.result.sessions[0].last_message_preview").value("Theo dữ liệu FHIR..."));
    }

    @Test
    void chatSessionsSearchByQuery() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000403");
        when(chatApplicationService.sessions("thuoc", 30))
                .thenReturn(new ChatSessionListResponse(List.of(new ChatSessionSummary(
                        sessionId,
                        "Thuoc cua benh nhan 002",
                        OffsetDateTime.parse("2026-06-01T10:00:00Z"),
                        OffsetDateTime.parse("2026-06-01T10:05:00Z"),
                        "BN2026-00002",
                        4,
                        "Benh nhan dang dung Amlodipine."
                ))));

        mockMvc.perform(get("/api/chat/sessions?query=thuoc&limit=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sessions[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.sessions[0].title").value("Thuoc cua benh nhan 002"))
                .andExpect(jsonPath("$.result.sessions[0].active_patient_id").value("BN2026-00002"))
                .andExpect(jsonPath("$.result.sessions[0].message_count").value(4));
    }

    @Test
    void chatSessionsRejectTooLongQuery() throws Exception {
        String query = "a".repeat(101);

        mockMvc.perform(get("/api/chat/sessions").param("query", query))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatSessionsRejectInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/chat/sessions?limit=0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatSessionMessagesReturnMessages() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        UUID userMessageId = UUID.fromString("00000000-0000-0000-0000-000000000501");
        UUID assistantMessageId = UUID.fromString("00000000-0000-0000-0000-000000000502");
        when(chatApplicationService.sessionMessages(sessionId))
                .thenReturn(new ChatMessagesResponse(
                        sessionId,
                        List.of(
                                new ChatMessageItem(
                                        userMessageId,
                                        "user",
                                        "Bệnh nhân này dùng thuốc gì?",
                                        OffsetDateTime.parse("2026-05-31T10:00:00Z"),
                                        null
                                ),
                                new ChatMessageItem(
                                        assistantMessageId,
                                        "assistant",
                                        "Theo dữ liệu FHIR...",
                                        OffsetDateTime.parse("2026-05-31T10:00:05Z"),
                                        new ChatMessageItem.Feedback(5, "Rất hữu ích")
                                )
                        )
                ));

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/messages", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.session_id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.messages[0].id").value(userMessageId.toString()))
                .andExpect(jsonPath("$.result.messages[0].role").value("user"))
                .andExpect(jsonPath("$.result.messages[0].content").value("Bệnh nhân này dùng thuốc gì?"))
                .andExpect(jsonPath("$.result.messages[0].feedback").isEmpty())
                .andExpect(jsonPath("$.result.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.result.messages[1].feedback.rating").value(5))
                .andExpect(jsonPath("$.result.messages[1].feedback.comment").value("Rất hữu ích"));
    }

    @Test
    void renameChatSessionReturnsUpdatedTitle() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        when(chatApplicationService.renameSession(sessionId, "Tên mới"))
                .thenReturn(new ChatSessionRenameResponse(sessionId, "Tên mới"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/chat/sessions/{sessionId}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Tên mới"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.title").value("Tên mới"));
    }

    @Test
    void renameChatSessionRejectsBlankTitle() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000602");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/chat/sessions/{sessionId}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteChatSessionReturnsNoContent() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000603");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/chat/sessions/{sessionId}", sessionId))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(chatApplicationService).deleteSession(sessionId);
    }

    @Test
    void quotaStatusReturnsDemoUserQuota() throws Exception {
        when(quotaService.currentUserStatus()).thenReturn(new QuotaStatusResponse(
                "demo_user",
                "user_standard",
                50,
                100000,
                new BigDecimal("1.00"),
                12,
                3000,
                500,
                3500,
                new BigDecimal("0.20"),
                38,
                96500,
                new BigDecimal("0.80"),
                true,
                null
        ));

        mockMvc.perform(get("/api/quota/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.user").value("demo_user"))
                .andExpect(jsonPath("$.result.policy").value("user_standard"))
                .andExpect(jsonPath("$.result.daily_request_limit").value(50))
                .andExpect(jsonPath("$.result.used_requests").value(12))
                .andExpect(jsonPath("$.result.remaining_requests").value(38))
                .andExpect(jsonPath("$.result.allowed").value(true));
    }

    @Test
    void costSummaryReturnsDemoUserCostSummary() throws Exception {
        when(costManagementService.currentUserCostSummary(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-01")
        )).thenReturn(new CostSummaryResponse(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-01"),
                2,
                3000,
                700,
                3700,
                new BigDecimal("0.002320"),
                List.of(new CostByModel(
                        "openai",
                        "gpt-4.1-mini",
                        2,
                        3000,
                        700,
                        3700,
                        new BigDecimal("0.002320")
                )),
                List.of(new CostByDay(
                        LocalDate.parse("2026-06-01"),
                        2,
                        3000,
                        700,
                        3700,
                        new BigDecimal("0.002320")
                )),
                List.of(new MissingPricingModel("openai", "custom-model", 1))
        ));

        mockMvc.perform(get("/api/usage/cost-summary?from=2026-06-01&to=2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.from").value("2026-06-01"))
                .andExpect(jsonPath("$.result.to").value("2026-06-01"))
                .andExpect(jsonPath("$.result.request_count").value(2))
                .andExpect(jsonPath("$.result.input_tokens").value(3000))
                .andExpect(jsonPath("$.result.output_tokens").value(700))
                .andExpect(jsonPath("$.result.total_tokens").value(3700))
                .andExpect(jsonPath("$.result.estimated_cost_usd").value(0.002320))
                .andExpect(jsonPath("$.result.models[0].llm_provider").value("openai"))
                .andExpect(jsonPath("$.result.models[0].llm_model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.result.days[0].date").value("2026-06-01"))
                .andExpect(jsonPath("$.result.missing_pricing_models[0].llm_model").value("custom-model"));
    }

    @Test
    void costSummaryRejectsInvalidDateRange() throws Exception {
        when(costManagementService.currentUserCostSummary(
                LocalDate.parse("2026-06-02"),
                LocalDate.parse("2026-06-01")
        )).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Invalid date range"
        ));

        mockMvc.perform(get("/api/usage/cost-summary?from=2026-06-02&to=2026-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modelPricingReturnsActivePricing() throws Exception {
        when(costManagementService.activePricing()).thenReturn(new ModelPricingListResponse(List.of(
                new ModelPricingInfo(
                        "openai",
                        "gpt-4.1-mini",
                        new BigDecimal("0.400000"),
                        new BigDecimal("1.600000"),
                        "USD"
                )
        )));

        mockMvc.perform(get("/api/model-pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pricing[0].provider").value("openai"))
                .andExpect(jsonPath("$.result.pricing[0].model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.result.pricing[0].input_price_per_1m_tokens").value(0.400000))
                .andExpect(jsonPath("$.result.pricing[0].output_price_per_1m_tokens").value(1.600000))
                .andExpect(jsonPath("$.result.pricing[0].currency").value("USD"));
    }
}
