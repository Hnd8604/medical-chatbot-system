package com.medicalchatbot.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionResponse;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.enums.ExportFormat;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.security.JwtAuthenticationFilter;
import com.medicalchatbot.backend.service.ChatExportService;
import com.medicalchatbot.backend.service.ChatExportService.ExportFile;
import com.medicalchatbot.backend.service.ChatService;
import com.medicalchatbot.backend.service.ChatSessionService;
import com.medicalchatbot.backend.service.FeedbackService;
import com.medicalchatbot.backend.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

@WebMvcTest({ChatController.class, ChatSessionController.class})
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ChatSessionService chatSessionService;

    @MockitoBean
    private ChatExportService chatExportService;

    @MockitoBean
    private FeedbackService feedbackService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void chatReturnsTheAnswerInSnakeCase() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(chatService.chat(any(ChatRequest.class))).thenReturn(new ChatResponse(
                sessionId, UUID.randomUUID(), "Theo dữ liệu FHIR...", "medications", "get_medication_requests",
                "llm", "llm", null, "BN2026-00001", null, false, null, false, null, null,
                objectMapper.readTree("[]"), null, objectMapper.readTree("{\"input_tokens\": 10}"), null,
                "trace-1", "answered", "fhir", null, objectMapper.readTree("{\"checked\": true, \"passed\": true}"), null));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": null, \"patient_id\": \"BN2026-00001\", \"message\": \"Thuốc?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.session_id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.tool_name").value("get_medication_requests"))
                .andExpect(jsonPath("$.result.answer_source").value("llm"))
                .andExpect(jsonPath("$.result.trace_id").value("trace-1"))
                .andExpect(jsonPath("$.result.response_status").value("answered"))
                .andExpect(jsonPath("$.result.usage.input_tokens").value(10))
                .andExpect(jsonPath("$.result.grounding.passed").value(true));
        verify(chatService).chat(new ChatRequest(null, "BN2026-00001", "Thuốc?"));
    }

    @Test
    void blankMessageIsAValidationError() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9009))
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));
    }

    @Test
    void exceededQuotaIsTooManyRequestsWithTheReason() throws Exception {
        QuotaStatusResponse status = new QuotaStatusResponse("demo", "user_standard", 1, 100000, BigDecimal.ONE,
                1, 0, 0, 0, BigDecimal.ZERO, 0, 100000, BigDecimal.ONE, false, "Đã vượt quá hạn mức 1 lượt gọi AI/ngày.");
        when(chatService.chat(any())).thenThrow(new QuotaExceededException(status));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("Đã vượt quá hạn mức 1 lượt gọi AI/ngày."));
    }

    @Test
    void agentPolicyRejectionIsForbiddenWithoutTheUpstreamBody() throws Exception {
        when(chatService.chat(any())).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                HttpHeaders.EMPTY, "{\"detail\":\"secret\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1102))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền truy cập tài nguyên này."));
    }

    @Test
    void sessionsAreAPlainList() throws Exception {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        when(chatSessionService.list("thuoc", 30)).thenReturn(List.of(new ChatSessionResponse(
                sessionId, "Thuốc của bệnh nhân 001", now, now, "BN2026-00001", 2, "Theo dữ liệu FHIR...")));

        mockMvc.perform(get("/api/chat/sessions?query=thuoc&limit=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result[0].active_patient_id").value("BN2026-00001"))
                .andExpect(jsonPath("$.result[0].message_count").value(2))
                .andExpect(jsonPath("$.result[0].last_message_preview").value("Theo dữ liệu FHIR..."));
    }

    @Test
    void sessionListRejectsBadParameters() throws Exception {
        mockMvc.perform(get("/api/chat/sessions").param("query", "x".repeat(101))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/chat/sessions?limit=0")).andExpect(status().isBadRequest());
    }

    @Test
    void messagesIncludeFeedbackAndTurnOutcome() throws Exception {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        when(chatSessionService.messages(sessionId)).thenReturn(List.of(
                new ChatMessageResponse(UUID.randomUUID(), "user", "Thuốc gì?", now, null, null, null, null, null, null),
                new ChatMessageResponse(UUID.randomUUID(), "assistant", "Amlodipine", now,
                        new ChatMessageResponse.Feedback(5, "Rất hữu ích"), "partial", "trace-502",
                        "template_grounding_fallback", new ChatMessageResponse.Grounding(true, false), null)));

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/messages", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].role").value("user"))
                .andExpect(jsonPath("$.result[0].feedback").isEmpty())
                .andExpect(jsonPath("$.result[1].feedback.rating").value(5))
                .andExpect(jsonPath("$.result[1].response_status").value("partial"))
                .andExpect(jsonPath("$.result[1].answer_source").value("template_grounding_fallback"))
                .andExpect(jsonPath("$.result[1].grounding.checked").value(true))
                .andExpect(jsonPath("$.result[1].grounding.passed").value(false));
    }

    @Test
    void renameReturnsTheNewTitle() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(chatSessionService.rename(sessionId, "Tên mới")).thenReturn(new ChatSessionRenameResponse(sessionId, "Tên mới"));

        mockMvc.perform(patch("/api/chat/sessions/{sessionId}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Tên mới\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("Tên mới"));
    }

    @Test
    void renameRejectsBlankTitle() throws Exception {
        mockMvc.perform(patch("/api/chat/sessions/{sessionId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturnsNoContentWithoutBody() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(delete("/api/chat/sessions/{sessionId}", sessionId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(chatSessionService).delete(sessionId);
    }

    @Test
    void exportIsADownloadNotWrappedInTheEnvelope() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(chatExportService.exportSession(sessionId, ExportFormat.CSV))
                .thenReturn(new ExportFile("session_" + sessionId + ".csv", ExportFormat.CSV, "a,b".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/export?format=csv", sessionId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"session_" + sessionId + ".csv\""))
                .andExpect(content().string("a,b"));
    }

    @Test
    void createFeedbackReturnsCreated() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(feedbackService.create(eq(messageId), any(FeedbackRequest.class)))
                .thenReturn(new FeedbackResponse(UUID.randomUUID(), messageId, 4, "ok"));

        mockMvc.perform(post("/api/chat/messages/{messageId}/feedback", messageId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 4, \"comment\": \"ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.message_id").value(messageId.toString()));
    }

    @Test
    void feedbackRatingOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/api/chat/messages/{messageId}/feedback", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 6}"))
                .andExpect(status().isBadRequest());
    }
}
