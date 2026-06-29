package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.ChatContextMessage;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionListResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.dto.request.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.QuotaExceededException;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ChatbotServiceClient chatbotServiceClient;

    @Mock
    private QuotaService quotaService;

    @Mock
    private CostEstimationService costEstimationService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private UserPatientLinkRepository userPatientLinkRepository;

    @Test
    void sessionMessagesRejectsSessionOutsideDemoUser() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        ChatApplicationService service = newService();

        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.existsForUser(sessionId, userId)).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.sessionMessages(sessionId)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(chatSessionRepository, never()).findMessagesForSession(sessionId, userId);
    }

    @Test
    void chatUsesSessionMemoryWhenRequestHasNoPatientId() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        ChatSession session = new ChatSession(sessionId);
        session.applyMemory(new ChatSessionMemory(
                "demo-patient-001",
                "Da xem huyet ap cua demo-patient-001.",
                "observations",
                "get_observations",
                "Observation",
                "obs-1"
        ));
        ChatApplicationService service = newService();
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "answer": "Theo du lieu FHIR...",
                  "intent": "medications",
                  "tool_name": "get_medication_requests",
                  "patient_id": "demo-patient-001",
                  "evidence": [
                    {
                      "resource_type": "MedicationRequest",
                      "id": "med-1",
                      "summary": "Amlodipine"
                    }
                  ],
                  "memory_update": {
                    "active_patient_id": "demo-patient-001",
                    "last_intent": "medications",
                    "last_tool_name": "get_medication_requests",
                    "last_resource_type": "MedicationRequest",
                    "last_resource_id": "med-1",
                    "summary": "Da xem thuoc cua demo-patient-001.",
                    "evidence_refs": [
                      {
                        "resource_type": "MedicationRequest",
                        "resource_id": "med-1",
                        "summary": "Amlodipine"
                      }
                    ]
                  },
                  "usage": {
                    "input_tokens": 0,
                    "output_tokens": 0,
                    "estimated_cost_usd": 0
                  }
                }
                """);

        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.DOCTOR);
        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(chatSessionRepository.existsForUser(sessionId, userId)).thenReturn(true);
        when(chatSessionRepository.getReferenceById(sessionId)).thenReturn(session);
        when(chatSessionRepository.findRecentMessagesForContext(sessionId, userId, 6)).thenReturn(List.of(
                new ChatContextMessage("user", "huyet ap cua benh nhan nay"),
                new ChatContextMessage("assistant", "Huyet ap 150/92 mmHg")
        ));
        when(chatbotServiceClient.chat(any(ChatbotChatRequest.class))).thenReturn(response);
        mockAssistantMessageSave();

        ChatResponse result = service.chat(new ChatRequest(
                sessionId,
                null,
                "benh nhan do dang dung thuoc gi?"
        ));

        ArgumentCaptor<ChatbotChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatbotChatRequest.class);
        verify(chatbotServiceClient).chat(requestCaptor.capture());
        ChatbotChatRequest chatbotRequest = requestCaptor.getValue();

        assertEquals("DOCTOR", chatbotRequest.userRole());
        assertEquals("demo-patient-001", chatbotRequest.patientId());
        assertEquals(List.of(), chatbotRequest.allowedPatientIds());
        assertEquals("STAFF", chatbotRequest.patientScope());
        assertEquals("demo-patient-001", chatbotRequest.conversationContext().activePatientId());
        assertEquals("Observation", chatbotRequest.conversationContext().lastResourceType());
        assertEquals(2, chatbotRequest.conversationContext().recentMessages().size());
        assertEquals("demo-patient-001", result.patientId());

        ArgumentCaptor<ChatSessionMemory> memoryCaptor = ArgumentCaptor.forClass(ChatSessionMemory.class);
        verify(chatSessionRepository).updateMemory(org.mockito.ArgumentMatchers.eq(session), memoryCaptor.capture());
        ChatSessionMemory savedMemory = memoryCaptor.getValue();
        assertEquals("demo-patient-001", savedMemory.activePatientId());
        assertEquals("medications", savedMemory.lastIntent());
        assertEquals("MedicationRequest", savedMemory.lastResourceType());
        assertEquals("med-1", savedMemory.lastResourceId());
    }

    @Test
    void allPatientResponseDoesNotOverwriteActivePatientContext() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000602");
        ChatSession session = new ChatSession(sessionId);
        session.applyMemory(new ChatSessionMemory(
                "demo-patient-001",
                "Da xem huyet ap cua demo-patient-001.",
                "observations",
                "get_observations",
                "Observation",
                "obs-1"
        ));
        ChatApplicationService service = newService();
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "answer": "Danh sach benh nhan...",
                  "intent": "patients",
                  "tool_name": "search_patients",
                  "all_patients": true,
                  "patient_id": null,
                  "evidence": [],
                  "memory_update": {
                    "last_intent": "patients",
                    "last_tool_name": "search_patients",
                    "summary": "Da xem danh sach benh nhan."
                  },
                  "usage": {
                    "input_tokens": 0,
                    "output_tokens": 0,
                    "estimated_cost_usd": 0
                  }
                }
                """);

        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.DOCTOR);
        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(chatSessionRepository.existsForUser(sessionId, userId)).thenReturn(true);
        when(chatSessionRepository.getReferenceById(sessionId)).thenReturn(session);
        when(chatSessionRepository.findRecentMessagesForContext(sessionId, userId, 6)).thenReturn(List.of());
        when(chatbotServiceClient.chat(any(ChatbotChatRequest.class))).thenReturn(response);
        mockAssistantMessageSave();

        service.chat(new ChatRequest(sessionId, null, "danh sach benh nhan"));

        ArgumentCaptor<ChatSessionMemory> memoryCaptor = ArgumentCaptor.forClass(ChatSessionMemory.class);
        verify(chatSessionRepository).updateMemory(org.mockito.ArgumentMatchers.eq(session), memoryCaptor.capture());
        ChatSessionMemory savedMemory = memoryCaptor.getValue();
        assertEquals("demo-patient-001", savedMemory.activePatientId());
        assertEquals("Observation", savedMemory.lastResourceType());
        assertEquals("obs-1", savedMemory.lastResourceId());
    }

    @Test
    void chatRejectsRequestBeforeCreatingSessionWhenQuotaExceeded() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        ChatApplicationService service = newService();
        QuotaStatusResponse quotaStatus = new QuotaStatusResponse(
                null,
                "free",
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

        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(currentUserService.requireCurrentUser()).thenReturn(user);
        doThrow(new QuotaExceededException(quotaStatus.blockedReason(), quotaStatus))
                .when(quotaService)
                .assertQuotaAvailable(userId);

        assertThrows(
                QuotaExceededException.class,
                () -> service.chat(new ChatRequest(null, null, "danh sach benh nhan"))
        );

        verify(chatSessionRepository, never()).create(any(), any());
        verify(chatMessageRepository, never()).save(any(), any(), any(), any());
        verify(chatbotServiceClient, never()).chat(any());
    }

    @Test
    void userChatUsesPrimaryLinkedPatientWhenNoPatientRequested() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.USER);
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000604");
        ChatSession session = new ChatSession(sessionId);
        ChatApplicationService service = newService();
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "answer": "Thuoc cua toi...",
                  "intent": "medications",
                  "tool_name": "get_medication_requests",
                  "patient_id": "demo-patient-001",
                  "evidence": [],
                  "memory_update": {
                    "active_patient_id": "demo-patient-001"
                  },
                  "usage": {
                    "input_tokens": 0,
                    "output_tokens": 0,
                    "estimated_cost_usd": 0
                  }
                }
                """);

        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(userPatientLinkRepository.findPatientIdsForUser(userId)).thenReturn(List.of("demo-patient-001"));
        when(chatSessionRepository.create(eq(user), any())).thenReturn(session);
        when(chatSessionRepository.findRecentMessagesForContext(sessionId, userId, 6)).thenReturn(List.of());
        when(chatbotServiceClient.chat(any(ChatbotChatRequest.class))).thenReturn(response);
        mockAssistantMessageSave();

        service.chat(new ChatRequest(null, null, "Toi dang dung thuoc gi?"));

        ArgumentCaptor<ChatbotChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatbotChatRequest.class);
        verify(chatbotServiceClient).chat(requestCaptor.capture());
        ChatbotChatRequest chatbotRequest = requestCaptor.getValue();

        assertEquals("USER", chatbotRequest.userRole());
        assertEquals("demo-patient-001", chatbotRequest.patientId());
        assertEquals(List.of("demo-patient-001"), chatbotRequest.allowedPatientIds());
        assertEquals("SELF", chatbotRequest.patientScope());
    }

    @Test
    void userChatRejectsPatientOutsideLinkedScope() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.USER);
        ChatApplicationService service = newService();

        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(userPatientLinkRepository.findPatientIdsForUser(userId)).thenReturn(List.of("demo-patient-001"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.chat(new ChatRequest(null, "demo-patient-002", "Thuoc cua Patient/demo-patient-002"))
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(chatSessionRepository, never()).create(any(), any());
        verify(chatbotServiceClient, never()).chat(any());
    }

    @Test
    void chatSavesSpringEstimatedCostWhenUsageHasTokens() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.DOCTOR);
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000603");
        ChatSession session = new ChatSession(sessionId);
        ChatApplicationService service = newService();
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "answer": "Theo du lieu FHIR...",
                  "intent": "patients",
                  "tool_name": "search_patients",
                  "llm_provider": "openai",
                  "llm_model": "gpt-4.1-mini",
                  "evidence": [],
                  "memory_update": {},
                  "usage": {
                    "input_tokens": 1000,
                    "output_tokens": 500,
                    "estimated_cost_usd": 0
                  }
                }
                """);

        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(chatSessionRepository.create(eq(user), any())).thenReturn(session);
        when(chatSessionRepository.findRecentMessagesForContext(sessionId, userId, 6)).thenReturn(List.of());
        when(chatbotServiceClient.chat(any(ChatbotChatRequest.class))).thenReturn(response);
        when(costEstimationService.estimateUsd(
                eq("openai"),
                eq("gpt-4.1-mini"),
                eq(1000),
                eq(500),
                eq(BigDecimal.ZERO)
        )).thenReturn(new BigDecimal("0.001200"));
        mockAssistantMessageSave();

        service.chat(new ChatRequest(null, null, "danh sach benh nhan"));

        verify(usageLogRepository).save(
                eq(user),
                eq(session),
                eq("openai"),
                eq("gpt-4.1-mini"),
                eq("chat"),
                eq("success"),
                anyLong(),
                eq(1000),
                eq(500),
                eq(new BigDecimal("0.001200")),
                isNull(),
                isNull(),
                eq(0),
                eq(BigDecimal.ZERO)
        );
    }

    @Test
    void sessionsUsesRecentSessionsWhenQueryIsBlank() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        ChatApplicationService service = newService();
        ChatSessionSummary summary = new ChatSessionSummary(
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                "Recent chat",
                OffsetDateTime.parse("2026-06-01T10:00:00Z"),
                OffsetDateTime.parse("2026-06-01T10:05:00Z"),
                "demo-patient-001",
                2,
                "Latest answer"
        );

        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.findRecentSessionsForUser(userId, 20)).thenReturn(List.of(summary));

        ChatSessionListResponse result = service.sessions("   ", 20);

        assertEquals(List.of(summary), result.sessions());
        verify(chatSessionRepository).findRecentSessionsForUser(userId, 20);
        verify(chatSessionRepository, never()).searchSessionsForUser(any(), any(), anyInt());
    }

    @Test
    void sessionsUsesSearchWhenQueryHasText() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        ChatApplicationService service = newService();
        ChatSessionSummary summary = new ChatSessionSummary(
                UUID.fromString("00000000-0000-0000-0000-000000000702"),
                "Medication chat",
                OffsetDateTime.parse("2026-06-02T10:00:00Z"),
                OffsetDateTime.parse("2026-06-02T10:05:00Z"),
                "demo-patient-002",
                4,
                "Amlodipine"
        );

        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.searchSessionsForUser(userId, "thuoc", 30)).thenReturn(List.of(summary));

        ChatSessionListResponse result = service.sessions("  thuoc  ", 30);

        assertEquals(List.of(summary), result.sessions());
        verify(chatSessionRepository).searchSessionsForUser(userId, "thuoc", 30);
        verify(chatSessionRepository, never()).findRecentSessionsForUser(any(), anyInt());
    }

    private ChatApplicationService newService() {
        return new ChatApplicationService(
                chatSessionRepository,
                chatMessageRepository,
                usageLogRepository,
                auditLogRepository,
                chatbotServiceClient,
                quotaService,
                costEstimationService,
                new ObjectMapper(),
                currentUserService,
                new UserPatientScopeService(userPatientLinkRepository)
        );
    }

    private void mockAssistantMessageSave() {
        ChatMessage assistantMessage = org.mockito.Mockito.mock(ChatMessage.class);
        when(assistantMessage.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000801"));
        when(chatMessageRepository.saveAndReturn(
                any(ChatSession.class),
                eq(ChatMessageRole.ASSISTANT),
                any(),
                any()
        )).thenReturn(assistantMessage);
    }
}
