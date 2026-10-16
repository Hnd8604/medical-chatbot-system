package com.medicalchatbot.backend.service;

import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.service.PatientScopeService.PatientScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs one chat turn: checks quota and patient scope, sends the question with the conversation
 * context to chatbot-service, then stores the answer, the new session memory and the usage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** Messages of the session sent along as conversation context. */
    private static final int CONTEXT_MESSAGE_LIMIT = 8;
    private static final int TITLE_MAX_LENGTH = 80;
    private static final String DEFAULT_TITLE = "Cuộc trò chuyện mới";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatbotServiceClient chatbotServiceClient;
    private final ChatSessionService chatSessionService;
    private final CurrentUserService currentUserService;
    private final QuotaService quotaService;
    private final PatientScopeService patientScopeService;
    private final ChatInteractionRecorder interactionRecorder;
    private final AlertService alertService;
    private final ChatMapper chatMapper;
    private final ChatbotResponseMapper chatbotResponseMapper;

    @Transactional
    public ChatResponse chat(ChatRequest request) {
        User user = currentUserService.requireCurrentUser();
        QuotaStatusResponse quota = quotaService.assertQuotaAvailable(user);

        // A session that existed before this turn survives a failed turn; a new one is rolled back.
        ChatSession existingSession = request.sessionId() == null
                ? null
                : chatSessionService.requireOwnedSession(request.sessionId(), user.getId());
        ChatMemory storedMemory = existingSession == null ? ChatMemory.EMPTY : existingSession.getMemory();
        PatientScope scope = patientScopeService.resolve(user, request.patientId(), storedMemory);
        ChatSession session = existingSession != null ? existingSession : startSession(user, request.message());
        ChatMemory memory = scope.restrict(storedMemory);

        ChatbotChatRequest chatbotRequest = buildChatbotRequest(user, session, request, scope, memory, quota);
        saveMessage(session, ChatMessageRole.USER, request.message(),
                chatbotResponseMapper.userMessageMetadata(request.patientId(), scope.effectivePatientId()));

        long startedAt = System.nanoTime();
        ChatbotChatResponse reply = askChatbot(chatbotRequest, user, existingSession, startedAt);
        long latencyMs = elapsedMillis(startedAt);
        try {
            return completeTurn(user, session, request, scope, memory, reply, latencyMs);
        } catch (RuntimeException exception) {
            // The LLM has already run (and cost money) but this transaction is about to roll back.
            recordSpendingOfFailedTurn(() -> interactionRecorder.recordUnsavedTurn(user, existingSession, reply, latencyMs));
            throw exception;
        }
    }

    private ChatbotChatRequest buildChatbotRequest(
            User user,
            ChatSession session,
            ChatRequest request,
            PatientScope scope,
            ChatMemory memory,
            QuotaStatusResponse quota
    ) {
        // Counted before the current question is stored, so chatbot-service sees the same history
        // size as the recent messages it receives.
        int previousMessageCount = (int) chatMessageRepository.countBySessionId(session.getId());
        return new ChatbotChatRequest(
                user.getId().toString(),
                user.getRole().name(),
                session.getId().toString(),
                request.message(),
                scope.effectivePatientId(),
                scope.allowedPatientIds(),
                scope.name(),
                chatMapper.toConversationContext(
                        memory,
                        chatSessionRepository.findRecentMessages(session.getId(), user.getId(), CONTEXT_MESSAGE_LIMIT),
                        previousMessageCount
                ),
                quotaService.usedRatio(quota)
        );
    }

    /**
     * A turn can fail half-way after it already paid for LLM calls (e.g. routing done, then FHIR
     * failed or the plan was blocked by policy); chatbot-service lists those calls in its error body.
     */
    private ChatbotChatResponse askChatbot(
            ChatbotChatRequest request,
            User user,
            ChatSession existingSession,
            long startedAt
    ) {
        try {
            return chatbotServiceClient.chat(request);
        } catch (RestClientResponseException exception) {
            long latencyMs = elapsedMillis(startedAt);
            recordSpendingOfFailedTurn(() -> interactionRecorder.recordFailedTurn(
                    user,
                    existingSession,
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsByteArray(),
                    latencyMs
            ));
            throw exception;
        }
    }

    /** Bookkeeping of a failed turn must never replace the error the user should see. */
    private void recordSpendingOfFailedTurn(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException recordingError) {
            log.warn("Could not record the LLM usage of a failed chat turn ({})",
                    recordingError.getClass().getSimpleName());
        }
    }

    private ChatResponse completeTurn(
            User user,
            ChatSession session,
            ChatRequest request,
            PatientScope scope,
            ChatMemory memory,
            ChatbotChatResponse reply,
            long latencyMs
    ) {
        ChatMessage answer = saveMessage(session, ChatMessageRole.ASSISTANT, reply.answer(),
                chatbotResponseMapper.assistantMessageMetadata(reply));
        session.updateMemory(chatbotResponseMapper.nextMemory(memory, scope.effectivePatientId(), reply));
        interactionRecorder.recordCompletedTurn(
                user, session, request.patientId(), scope.effectivePatientId(), reply, latencyMs);

        // chatbot-service answers HTTP 200 even when every retrieval failed, so no exception reports it.
        if (reply.hasToolError()) {
            alertService.raise(AlertType.CHATBOT_TOOL_ERROR, "Dịch vụ AI không truy xuất được dữ liệu FHIR.",
                    chatbotResponseMapper.toolErrorAlertMetadata(reply));
        }
        return chatbotResponseMapper.toResponse(session.getId(), answer.getId(), reply);
    }

    private ChatSession startSession(User user, String firstMessage) {
        return chatSessionRepository.save(ChatSession.builder()
                .user(user)
                .title(titleFrom(firstMessage))
                .build());
    }

    private ChatMessage saveMessage(ChatSession session, ChatMessageRole role, String content, JsonNode metadata) {
        session.touch();
        return chatMessageRepository.save(ChatMessage.builder()
                .session(session)
                .role(role)
                .content(content)
                .metadataJson(metadata)
                .build());
    }

    private static String titleFrom(String message) {
        String title = message == null ? "" : message.strip();
        if (title.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return title.length() <= TITLE_MAX_LENGTH ? title : title.substring(0, TITLE_MAX_LENGTH);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
