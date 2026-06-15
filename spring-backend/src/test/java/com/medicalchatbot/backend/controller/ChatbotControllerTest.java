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
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.dto.response.CostByDay;
import com.medicalchatbot.backend.dto.response.CostByModel;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.MissingPricingModel;
import com.medicalchatbot.backend.dto.response.ModelPricingInfo;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.service.ChatApplicationService;
import com.medicalchatbot.backend.service.ChatbotServiceClient;
import com.medicalchatbot.backend.service.CostManagementService;
import com.medicalchatbot.backend.service.QuotaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatbotController.class)
class ChatbotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatbotServiceClient chatbotServiceClient;

    @MockitoBean
    private ChatApplicationService chatApplicationService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private CostManagementService costManagementService;

    @Test
    void patientReturnsChatbotServicePayload() throws Exception {
        when(chatbotServiceClient.getPatient("demo-patient-001"))
                .thenReturn(objectMapper.readTree("""
                        {
                          "id": "demo-patient-001",
                          "name": "Van A Nguyen"
                        }
                        """));

        mockMvc.perform(get("/api/patients/demo-patient-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("demo-patient-001"))
                .andExpect(jsonPath("$.name").value("Van A Nguyen"));
    }

    @Test
    void searchPatientsReturnsChatbotServicePayload() throws Exception {
        when(chatbotServiceClient.searchPatients("Nguyen Van A", null, "2003-01-01", null, 20))
                .thenReturn(objectMapper.readTree("""
                        {
                          "criteria": {
                            "name": "Nguyen Van A",
                            "birth_date": "2003-01-01"
                          },
                          "patients": [
                            {
                              "id": "demo-patient-001",
                              "name": "Van A Nguyen"
                            }
                          ]
                        }
                        """));

        mockMvc.perform(get("/api/patients?name=Nguyen Van A&birth_date=2003-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patients[0].id").value("demo-patient-001"))
                .andExpect(jsonPath("$.criteria.name").value("Nguyen Van A"));
    }

    @Test
    void observationsRejectInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/patients/demo-patient-001/observations?limit=100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dữ liệu yêu cầu không hợp lệ."));
    }

    @Test
    void encountersReturnChatbotServicePayload() throws Exception {
        when(chatbotServiceClient.getPatientEncounters("demo-patient-005", 5))
                .thenReturn(objectMapper.readTree("""
                        {
                          "patient_id": "demo-patient-005",
                          "encounters": [
                            {
                              "id": "demo-encounter-006",
                              "status": "finished"
                            }
                          ]
                        }
                        """));

        mockMvc.perform(get("/api/patients/demo-patient-005/encounters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patient_id").value("demo-patient-005"))
                .andExpect(jsonPath("$.encounters[0].id").value("demo-encounter-006"));
    }

    @Test
    void chatReturnsPersistedSessionResponse() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        when(chatApplicationService.chat(new ChatRequest(null, "demo-patient-001", "Bệnh nhân 001 đang dùng thuốc gì?")))
                .thenReturn(new ChatResponse(
                        sessionId,
                        null,
                        "Theo dữ liệu FHIR hiện có...",
                        "medications",
                        "get_medication_requests",
                        "llm",
                        "llm",
                        null,
                        "demo-patient-001",
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
                                  "patient_id": "demo-patient-001",
                                  "message": "Bệnh nhân 001 đang dùng thuốc gì?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId.toString()))
                .andExpect(jsonPath("$.intent").value("medications"))
                .andExpect(jsonPath("$.tool_name").value("get_medication_requests"))
                .andExpect(jsonPath("$.intent_source").value("llm"))
                .andExpect(jsonPath("$.answer_source").value("llm"))
                .andExpect(jsonPath("$.patient_id").value("demo-patient-001"));
    }

    @Test
    void chatReturnsTooManyRequestsWhenQuotaExceeded() throws Exception {
        QuotaStatusResponse quotaStatus = new QuotaStatusResponse(
                "demo_user",
                "free_demo",
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
                .andExpect(jsonPath("$.detail").value("Đã vượt quá hạn mức 1 lượt gọi AI/ngày."));
    }

    @Test
    void chatSessionsReturnRecentSessions() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        when(chatApplicationService.recentSessions(20))
                .thenReturn(new ChatSessionListResponse(List.of(new ChatSessionSummary(
                        sessionId,
                        "Thuốc của bệnh nhân 001",
                        OffsetDateTime.parse("2026-05-31T10:00:00Z"),
                        OffsetDateTime.parse("2026-05-31T10:05:00Z"),
                        "demo-patient-001",
                        2,
                        "Theo dữ liệu FHIR..."
                ))));

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$.sessions[0].title").value("Thuốc của bệnh nhân 001"))
                .andExpect(jsonPath("$.sessions[0].active_patient_id").value("demo-patient-001"))
                .andExpect(jsonPath("$.sessions[0].message_count").value(2))
                .andExpect(jsonPath("$.sessions[0].last_message_preview").value("Theo dữ liệu FHIR..."));
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
                                        OffsetDateTime.parse("2026-05-31T10:00:00Z")
                                ),
                                new ChatMessageItem(
                                        assistantMessageId,
                                        "assistant",
                                        "Theo dữ liệu FHIR...",
                                        OffsetDateTime.parse("2026-05-31T10:00:05Z")
                                )
                        )
                ));

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/messages", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId.toString()))
                .andExpect(jsonPath("$.messages[0].id").value(userMessageId.toString()))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Bệnh nhân này dùng thuốc gì?"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"));
    }

    @Test
    void quotaStatusReturnsDemoUserQuota() throws Exception {
        when(quotaService.demoUserStatus()).thenReturn(new QuotaStatusResponse(
                "demo_user",
                "free_demo",
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
                .andExpect(jsonPath("$.user").value("demo_user"))
                .andExpect(jsonPath("$.policy").value("free_demo"))
                .andExpect(jsonPath("$.daily_request_limit").value(50))
                .andExpect(jsonPath("$.used_requests").value(12))
                .andExpect(jsonPath("$.remaining_requests").value(38))
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void costSummaryReturnsDemoUserCostSummary() throws Exception {
        when(costManagementService.demoUserCostSummary(
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
                .andExpect(jsonPath("$.from").value("2026-06-01"))
                .andExpect(jsonPath("$.to").value("2026-06-01"))
                .andExpect(jsonPath("$.request_count").value(2))
                .andExpect(jsonPath("$.input_tokens").value(3000))
                .andExpect(jsonPath("$.output_tokens").value(700))
                .andExpect(jsonPath("$.total_tokens").value(3700))
                .andExpect(jsonPath("$.estimated_cost_usd").value(0.002320))
                .andExpect(jsonPath("$.models[0].llm_provider").value("openai"))
                .andExpect(jsonPath("$.models[0].llm_model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.days[0].date").value("2026-06-01"))
                .andExpect(jsonPath("$.missing_pricing_models[0].llm_model").value("custom-model"));
    }

    @Test
    void costSummaryRejectsInvalidDateRange() throws Exception {
        when(costManagementService.demoUserCostSummary(
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
                .andExpect(jsonPath("$.pricing[0].provider").value("openai"))
                .andExpect(jsonPath("$.pricing[0].model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.pricing[0].input_price_per_1m_tokens").value(0.400000))
                .andExpect(jsonPath("$.pricing[0].output_price_per_1m_tokens").value(1.600000))
                .andExpect(jsonPath("$.pricing[0].currency").value("USD"));
    }
}
