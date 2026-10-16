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
import com.medicalchatbot.backend.dto.request.chat.ChatRequest;
import com.medicalchatbot.backend.dto.request.chat.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.chat.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionResponse;
import com.medicalchatbot.backend.dto.response.chat.FeedbackResponse;
import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import com.medicalchatbot.backend.enums.ExportFormat;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.service.ChatExportService.ExportFile;
import com.medicalchatbot.backend.service.ChatExportService;
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
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void chatReturnsTheAnswerInSnakeCase() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(chatService.chat(any(ChatRequest.class))).thenReturn(new ChatResponse(
                sessionId, UUID.randomUUID(), "Theo dữ liệu FHIR...", "medications", "get_medication_requests",
                "llm", "llm", null, "BN2026-00001", null, false, null, false, null, null,
                objectMapper.readTree("[]"), null, objectMapper.readTree("{\"input_tokens\": 10}"), null,
                "trace-1", "answered", "fhir", null, objectMapper.readTree("{\"checked\": true, \"passed\": true}"), null));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": null, \"patientId\": \"BN2026-00001\", \"message\": \"Thuốc?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.result.toolName").value("get_medication_requests"))
                .andExpect(jsonPath("$.result.answerSource").value("llm"))
                .andExpect(jsonPath("$.result.traceId").value("trace-1"))
                .andExpect(jsonPath("$.result.responseStatus").value("answered"))
                .andExpect(jsonPath("$.result.usage.input_tokens").value(10))
                .andExpect(jsonPath("$.result.grounding.passed").value(true));
        verify(chatService).chat(new ChatRequest(null, "BN2026-00001", "Thuốc?"));
    }

    @Test
    void blankMessageIsAValidationError() throws Exception {
        mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9009))
                .andExpect(jsonPath("$.message").value("Vui lòng nhập câu hỏi."));
    }

    @Test
    void exceededQuotaIsTooManyRequestsWithTheReason() throws Exception {
        QuotaStatusResponse status = new QuotaStatusResponse("demo", "user_standard", 1, 100000, BigDecimal.ONE,
                1, 0, 0, 0, BigDecimal.ZERO, 0, 100000, BigDecimal.ONE, false, "Đã vượt quá hạn mức 1 lượt gọi AI/ngày.");
        when(chatService.chat(any())).thenThrow(new QuotaExceededException(status));

        mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("Đã vượt quá hạn mức 1 lượt gọi AI/ngày."));
    }

    @Test
    void agentPolicyRejectionIsForbiddenWithoutTheUpstreamBody() throws Exception {
        when(chatService.chat(any())).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                HttpHeaders.EMPTY, "{\"detail\":\"secret\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
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

        mockMvc.perform(get("/chat/sessions?query=thuoc&limit=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$.result[0].activePatientId").value("BN2026-00001"))
                .andExpect(jsonPath("$.result[0].messageCount").value(2))
                .andExpect(jsonPath("$.result[0].lastMessagePreview").value("Theo dữ liệu FHIR..."));
    }

    @Test
    void sessionListRejectsBadParameters() throws Exception {
        mockMvc.perform(get("/chat/sessions").param("query", "x".repeat(101))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/chat/sessions?limit=0")).andExpect(status().isBadRequest());
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

        mockMvc.perform(get("/chat/sessions/{sessionId}/messages", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].role").value("user"))
                .andExpect(jsonPath("$.result[0].feedback").isEmpty())
                .andExpect(jsonPath("$.result[1].feedback.rating").value(5))
                .andExpect(jsonPath("$.result[1].responseStatus").value("partial"))
                .andExpect(jsonPath("$.result[1].answerSource").value("template_grounding_fallback"))
                .andExpect(jsonPath("$.result[1].grounding.checked").value(true))
                .andExpect(jsonPath("$.result[1].grounding.passed").value(false));
    }

    @Test
    void renameReturnsTheNewTitle() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(chatSessionService.rename(sessionId, "Tên mới")).thenReturn(new ChatSessionRenameResponse(sessionId, "Tên mới"));

        mockMvc.perform(patch("/chat/sessions/{sessionId}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Tên mới\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("Tên mới"));
    }

    @Test
    void renameRejectsBlankTitle() throws Exception {
        mockMvc.perform(patch("/chat/sessions/{sessionId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteAnswersWithAMessage() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(delete("/chat/sessions/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Xóa cuộc trò chuyện thành công."));

        verify(chatSessionService).delete(sessionId);
    }

    @Test
    void exportIsADownloadNotWrappedInTheEnvelope() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(chatExportService.exportSession(sessionId, ExportFormat.CSV))
                .thenReturn(new ExportFile("session_" + sessionId + ".csv", ExportFormat.CSV, "a,b".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/chat/sessions/{sessionId}/export?format=csv", sessionId))
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

        mockMvc.perform(post("/chat/messages/{messageId}/feedback", messageId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 4, \"comment\": \"ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.messageId").value(messageId.toString()));
    }

    @Test
    void feedbackRatingOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/chat/messages/{messageId}/feedback", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 6}"))
                .andExpect(status().isBadRequest());
    }
}
