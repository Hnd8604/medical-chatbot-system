package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.request.chat.ChatRequest;
import com.medicalchatbot.backend.dto.response.chat.ChatResponse;
import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.enums.UsageStatus;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.mapper.ChatAuditMapper;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.LlmCallUsageRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID QUESTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000800");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private LlmCallUsageRepository llmCallUsageRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserPatientLinkRepository userPatientLinkRepository;

    @Mock
    private ChatbotServiceClient chatbotServiceClient;

    @Mock
    private QuotaService quotaService;

    @Mock
    private AlertService alertService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatService service;
    private User doctor;

    @BeforeEach
    void setUp() {
        ChatbotResponseMapper responseMapper = new ChatbotResponseMapper(objectMapper);
        ChatInteractionRecorder recorder = new ChatInteractionRecorder(
                usageLogRepository,
                llmCallUsageRepository,
                new AuditService(auditLogRepository, Mappers.getMapper(AuditLogMapper.class), objectMapper),
                new ChatAuditMapper(objectMapper, responseMapper),
                objectMapper
        );
        ChatMapper chatMapper = Mappers.getMapper(ChatMapper.class);
        ChatTurnService turnService = new ChatTurnService(
                chatSessionRepository,
                chatMessageRepository,
                userRepository,
                new ChatSessionService(chatSessionRepository, chatMapper),
                quotaService,
                new PatientScopeService(userPatientLinkRepository),
                recorder,
                alertService,
                chatMapper,
                responseMapper
        );
        service = new ChatService(turnService, chatbotServiceClient, recorder);
        doctor = TestUsers.user(UserRole.DOCTOR);
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void theChatbotIsAskedOutsideTheTransactionsOfTheTurn() throws NoSuchMethodException {
        // A transaction around chat() would hold a pooled connection for the whole chatbot call.
        assertFalse(ChatService.class.isAnnotationPresent(Transactional.class));
        assertFalse(ChatService.class.getMethod("chat", ChatRequest.class).isAnnotationPresent(Transactional.class));
        for (String step : List.of("prepare", "complete", "discard")) {
            boolean transactional = Arrays.stream(ChatTurnService.class.getMethods())
                    .filter(method -> method.getName().equals(step))
                    .allMatch(method -> method.isAnnotationPresent(Transactional.class));
            assertTrue(transactional, step + " must run in its own transaction");
        }
    }

    @Test
    void followUpQuestionUsesTheSessionMemory() throws Exception {
        ChatSession session = existingSession(doctor, new ChatMemory("BN2026-00001", "Đã xem huyết áp.",
                "observations", "get_observations", "Observation", "obs-1"));
        when(chatSessionRepository.findRecentMessages(session.getId(), doctor.getId(), 8)).thenReturn(List.of(
                new ChatContextMessageProjection("user", "huyết áp của bệnh nhân này"),
                new ChatContextMessageProjection("assistant", "Huyết áp 150/92 mmHg")));
        when(chatMessageRepository.countBySessionId(session.getId())).thenReturn(6L);
        chatbotAnswers("""
                {
                  "answer": "Theo dữ liệu FHIR...",
                  "intent": "medications",
                  "tool_name": "get_medication_requests",
                  "patient_id": "BN2026-00001",
                  "evidence": [{"resource_type": "MedicationRequest", "id": "med-1", "summary": "Amlodipine"}],
                  "memory_update": {
                    "active_patient_id": "BN2026-00001",
                    "last_intent": "medications",
                    "last_resource_type": "MedicationRequest",
                    "last_resource_id": "med-1",
                    "summary": "Đã xem thuốc."
                  }
                }
                """);

        ChatResponse result = service.chat(new ChatRequest(session.getId(), null, "bệnh nhân đó dùng thuốc gì?"));

        ChatbotChatRequest request = sentRequest();
        assertEquals("DOCTOR", request.userRole());
        assertEquals("BN2026-00001", request.patientId());
        assertEquals(List.of(), request.allowedPatientIds());
        assertEquals("STAFF", request.patientScope());
        assertEquals("Observation", request.conversationContext().lastResourceType());
        assertEquals(2, request.conversationContext().recentMessages().size());
        assertEquals(6, request.conversationContext().totalMessageCount());

        assertEquals(session.getId(), result.sessionId());
        assertEquals(ASSISTANT_MESSAGE_ID, result.messageId());
        ChatMemory memory = session.getMemory();
        assertEquals("BN2026-00001", memory.activePatientId());
        assertEquals("medications", memory.lastIntent());
        assertEquals("MedicationRequest", memory.lastResourceType());
        assertEquals("med-1", memory.lastResourceId());
    }

    @Test
    void allPatientAnswerKeepsTheActivePatientContext() throws Exception {
        ChatSession session = existingSession(doctor, new ChatMemory("BN2026-00001", "Đã xem huyết áp.",
                "observations", "get_observations", "Observation", "obs-1"));
        chatbotAnswers("""
                {"answer": "Danh sách bệnh nhân...", "intent": "list_patients", "tool_name": "search_patients",
                 "all_patients": true, "memory_update": {"summary": "Đã xem danh sách."}}
                """);

        service.chat(new ChatRequest(session.getId(), null, "danh sách bệnh nhân"));

        assertEquals("BN2026-00001", session.getMemory().activePatientId());
        assertEquals("obs-1", session.getMemory().lastResourceId());
        assertEquals("Đã xem danh sách.", session.getMemory().summary());
    }

    @Test
    void exceededQuotaRejectsTheTurnBeforeAnythingIsStored() {
        TestUsers.signIn(doctor, userRepository);
        when(quotaService.assertQuotaAvailable(doctor)).thenThrow(new QuotaExceededException(blockedStatus()));

        assertThrows(QuotaExceededException.class,
                () -> service.chat(new ChatRequest(null, null, "danh sách bệnh nhân")));

        verify(chatSessionRepository, never()).save(any());
        verify(chatMessageRepository, never()).save(any());
        verify(chatbotServiceClient, never()).chat(any());
    }

    @Test
    void userQuestionsAreScopedToTheirLinkedPatient() throws Exception {
        User user = TestUsers.user(UserRole.USER);
        newSession(user);
        when(userPatientLinkRepository.findPatientIdsByUserId(user.getId())).thenReturn(List.of("BN2026-00001"));
        chatbotAnswers("{\"answer\": \"Thuốc của bạn...\", \"tool_name\": \"get_medication_requests\", \"patient_id\": \"BN2026-00001\"}");

        service.chat(new ChatRequest(null, null, "Tôi đang dùng thuốc gì?"));

        ChatbotChatRequest request = sentRequest();
        assertEquals("USER", request.userRole());
        assertEquals("BN2026-00001", request.patientId());
        assertEquals(List.of("BN2026-00001"), request.allowedPatientIds());
        assertEquals("SELF", request.patientScope());
        assertEquals(0, request.conversationContext().totalMessageCount());
    }

    @Test
    void userCannotAskAboutAnotherPatient() {
        User user = TestUsers.user(UserRole.USER);
        TestUsers.signIn(user, userRepository);
        when(userPatientLinkRepository.findPatientIdsByUserId(user.getId())).thenReturn(List.of("BN2026-00001"));

        AppException exception = assertThrows(AppException.class,
                () -> service.chat(new ChatRequest(null, "Patient/BN2026-00002", "Thuốc của BN2026-00002")));

        assertEquals(ErrorCode.PATIENT_ACCESS_DENIED, exception.getErrorCode());
        verify(chatSessionRepository, never()).save(any());
        verify(chatbotServiceClient, never()).chat(any());
    }

    @Test
    void unknownSessionIsNotFound() {
        UUID sessionId = UUID.randomUUID();
        TestUsers.signIn(doctor, userRepository);
        when(chatSessionRepository.findByIdAndUserId(sessionId, doctor.getId())).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> service.chat(new ChatRequest(sessionId, null, "?")));

        assertEquals(ErrorCode.CHAT_SESSION_NOT_EXISTED, exception.getErrorCode());
    }

    @Test
    void completedTurnRecordsUsageAndAuditOnTheSession() throws Exception {
        ChatSession session = newSession(doctor);
        usageLogsAreSaved();
        chatbotAnswers("""
                {
                  "answer": "Theo dữ liệu FHIR...",
                  "tool_name": "search_patients",
                  "llm_provider": "openai",
                  "llm_model": "gpt-4.1-mini",
                  "trace_id": "trace-603",
                  "llm_calls": [
                    {"stage": "agent_router", "provider": "openai", "model": "gpt-4o-mini",
                     "input_tokens": 300, "output_tokens": 20, "cost_usd": 0.000057, "call_id": "c1"},
                    {"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                     "input_tokens": 1000, "output_tokens": 500, "cost_usd": null, "call_id": "c2"}
                  ]
                }
                """);

        service.chat(new ChatRequest(null, null, "danh sách bệnh nhân"));

        UsageLog usageLog = savedUsageLog();
        assertEquals(session, usageLog.getSession());
        assertEquals(UsageStatus.SUCCESS, usageLog.getStatus());
        assertEquals(1300, usageLog.getInputTokens());
        assertEquals(new BigDecimal("0.000057"), usageLog.getCostUsd());
        assertEquals("trace-603", usageLog.getTraceId());
        AuditLog audit = savedAudit();
        assertEquals("SEARCH_PATIENTS", audit.getAction());
        assertEquals(session, audit.getSession());
    }

    @Test
    void chatbotFailureRecordsTheCallsAlreadyPaidFor() {
        newSession(doctor);
        usageLogsAreSaved();
        // The router ran (and was paid for), then the plan was blocked by policy: HTTP 403.
        byte[] errorBody = """
                {"detail": "Bạn không có quyền xem dữ liệu này.",
                 "llm_calls": [{"stage": "agent_router", "provider": "openai", "model": "gpt-4o-mini",
                                "input_tokens": 300, "output_tokens": 20, "cost_usd": 0.000057}]}
                """.getBytes(StandardCharsets.UTF_8);
        when(chatbotServiceClient.chat(any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, errorBody, StandardCharsets.UTF_8));

        assertThrows(HttpClientErrorException.class,
                () -> service.chat(new ChatRequest(null, null, "danh sách bệnh nhân")));

        UsageLog usageLog = savedUsageLog();
        assertEquals(UsageStatus.FAILED, usageLog.getStatus());
        assertEquals(new BigDecimal("0.000057"), usageLog.getCostUsd());
        // The session opened by the failing turn is discarded, so it must not be referenced.
        assertNull(usageLog.getSession());
        assertEquals("CHAT_FAILED", savedAudit().getAction());
    }

    @Test
    void failedTurnDiscardsTheSessionItOpened() {
        ChatSession session = newSession(doctor);
        when(chatbotServiceClient.chat(any())).thenThrow(new ResourceAccessException("connection refused"));

        assertThrows(ResourceAccessException.class,
                () -> service.chat(new ChatRequest(null, null, "danh sách bệnh nhân")));

        verify(chatSessionRepository).deleteByIdAndUserId(session.getId(), doctor.getId());
        verify(chatMessageRepository, never()).deleteById(any());
    }

    @Test
    void failedTurnInAnExistingSessionDiscardsOnlyItsQuestion() {
        ChatSession session = existingSession(doctor, ChatMemory.EMPTY);
        when(chatbotServiceClient.chat(any())).thenThrow(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null));

        assertThrows(HttpServerErrorException.class,
                () -> service.chat(new ChatRequest(session.getId(), null, "huyết áp")));

        verify(chatMessageRepository).deleteById(QUESTION_ID);
        verify(chatSessionRepository, never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void failingCleanupDoesNotHideTheChatbotError() {
        ChatSession session = newSession(doctor);
        when(chatbotServiceClient.chat(any())).thenThrow(new ResourceAccessException("connection refused"));
        when(chatSessionRepository.deleteByIdAndUserId(session.getId(), doctor.getId()))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(ResourceAccessException.class,
                () -> service.chat(new ChatRequest(null, null, "danh sách bệnh nhân")));
    }

    @Test
    void storageFailureAfterTheAnswerStillRecordsItsCost() throws Exception {
        newSession(doctor);
        usageLogsAreSaved();
        when(chatbotServiceClient.chat(any())).thenReturn(reply("""
                {"answer": "Theo dữ liệu FHIR...", "tool_name": "search_patients",
                 "llm_calls": [{"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                                "input_tokens": 1000, "output_tokens": 500, "cost_usd": 0.0012}]}
                """));
        doAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (message.getRole() == ChatMessageRole.ASSISTANT) {
                throw new IllegalStateException("db down");
            }
            return message;
        }).when(chatMessageRepository).save(any(ChatMessage.class));

        assertThrows(IllegalStateException.class,
                () -> service.chat(new ChatRequest(null, null, "danh sách bệnh nhân")));

        UsageLog usageLog = savedUsageLog();
        assertEquals(UsageStatus.FAILED, usageLog.getStatus());
        assertEquals(new BigDecimal("0.001200"), usageLog.getCostUsd());
        assertNull(usageLog.getSession());
        verify(chatSessionRepository).deleteByIdAndUserId(any(), eq(doctor.getId()));
    }

    @Test
    void toolErrorTurnRaisesAnAlertAndIsNotAuditedAsDataAccess() throws Exception {
        ChatSession session = newSession(doctor);
        // FHIR failed at every step: chatbot-service still answers HTTP 200 with response_status = tool_error.
        chatbotAnswers("""
                {
                  "answer": "Hệ thống chưa lấy được dữ liệu FHIR.",
                  "tool_name": "get_conditions",
                  "response_status": "tool_error",
                  "trace_id": "trace-607",
                  "failed_steps": [{"id": "c", "tool": "get_conditions", "error_code": "fhir_error"}],
                  "llm_calls": []
                }
                """);

        ChatResponse result = service.chat(new ChatRequest(null, "BN2026-00001", "chẩn đoán của bệnh nhân"));

        assertEquals("tool_error", result.responseStatus());
        ArgumentCaptor<JsonNode> metadata = ArgumentCaptor.forClass(JsonNode.class);
        verify(alertService).raise(eq(AlertType.CHATBOT_TOOL_ERROR), any(), metadata.capture());
        assertEquals("trace-607", metadata.getValue().path("trace_id").asText());
        assertEquals("fhir_error", metadata.getValue().path("failed_steps").get(0).path("error_code").asText());
        AuditLog audit = savedAudit();
        assertEquals("CHAT_TOOL_ERROR", audit.getAction());
        assertEquals(session.getId().toString(), audit.getResourceId());
    }

    @Test
    void storesTheQuestionWithItsPatientScopeBeforeAskingTheChatbot() throws Exception {
        newSession(doctor);
        chatbotAnswers("{\"answer\": \"ok\"}");

        service.chat(new ChatRequest(null, "Patient/BN2026-00003", "  hỏi  "));

        ArgumentCaptor<ChatMessage> messages = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(messages.capture());
        ChatMessage question = messages.getAllValues().getFirst();
        assertEquals(ChatMessageRole.USER, question.getRole());
        assertEquals("Patient/BN2026-00003", question.getMetadataJson().path("request_patient_id").asText());
        assertEquals("BN2026-00003", question.getMetadataJson().path("effective_patient_id").asText());
        assertEquals("BN2026-00003", sentRequest().patientId());
    }

    private ChatSession existingSession(User user, ChatMemory memory) {
        ChatSession session = ChatSession.builder().id(UUID.randomUUID()).user(user).memory(memory).build();
        TestUsers.signIn(user, userRepository);
        when(chatSessionRepository.findByIdAndUserId(session.getId(), user.getId())).thenReturn(Optional.of(session));
        assistantMessagesGetAnId();
        return session;
    }

    private ChatSession newSession(User user) {
        ChatSession session = ChatSession.builder().id(UUID.randomUUID()).user(user).build();
        TestUsers.signIn(user, userRepository);
        when(chatSessionRepository.save(any(ChatSession.class))).thenReturn(session);
        when(chatSessionRepository.findRecentMessages(eq(session.getId()), eq(user.getId()), anyInt())).thenReturn(List.of());
        // The step that stores the answer reloads the session the turn opened.
        lenient().when(chatSessionRepository.findByIdAndUserId(session.getId(), user.getId())).thenReturn(Optional.of(session));
        assistantMessagesGetAnId();
        return session;
    }

    private void assistantMessagesGetAnId() {
        lenient().when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(message.getRole() == ChatMessageRole.ASSISTANT ? ASSISTANT_MESSAGE_ID : QUESTION_ID);
            return message;
        });
    }

    private void chatbotAnswers(String json) throws Exception {
        when(chatbotServiceClient.chat(any())).thenReturn(reply(json));
    }

    private ChatbotChatResponse reply(String json) throws Exception {
        return objectMapper.readValue(json, ChatbotChatResponse.class);
    }

    private void usageLogsAreSaved() {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ChatbotChatRequest sentRequest() {
        ArgumentCaptor<ChatbotChatRequest> request = ArgumentCaptor.forClass(ChatbotChatRequest.class);
        verify(chatbotServiceClient).chat(request.capture());
        return request.getValue();
    }

    private UsageLog savedUsageLog() {
        ArgumentCaptor<UsageLog> usageLog = ArgumentCaptor.forClass(UsageLog.class);
        verify(usageLogRepository).save(usageLog.capture());
        return usageLog.getValue();
    }

    private AuditLog savedAudit() {
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        return audit.getValue();
    }

    private static QuotaStatusResponse blockedStatus() {
        return new QuotaStatusResponse("doctor_demo", "staff", 1, 100000, BigDecimal.ONE, 1, 0, 0, 0,
                BigDecimal.ZERO, 0, 100000, BigDecimal.ONE, false, "Đã vượt quá hạn mức 1 lượt gọi AI/ngày.");
    }
}
