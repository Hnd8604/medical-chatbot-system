package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.request.ChatContextMessage;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.request.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.request.ConversationContext;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatMessagesResponse;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionListResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.dto.response.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.integration.client.ChatbotServiceClient;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatApplicationService {

    private static final int RECENT_CONTEXT_MESSAGE_LIMIT = 8;
    private static final int SESSION_SEARCH_QUERY_MAX_LENGTH = 100;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatbotServiceClient chatbotServiceClient;
    private final QuotaService quotaService;
    private final CurrentUserService currentUserService;
    private final UserPatientScopeService userPatientScopeService;
    private final LlmGatewayKeyService llmGatewayKeyService;
    private final ChatMapper chatMapper;
    private final ChatbotResponseMapper chatbotResponseMapper;
    private final ChatInteractionRecorder chatInteractionRecorder;

    @Transactional
    public ChatResponse chat(ChatRequest request) {
        User user = currentUserService.requireCurrentUser();
        UUID userId = user.getId();
        QuotaStatusResponse quotaStatus = quotaService.assertQuotaAvailable(userId);
        double quotaUsedRatio = quotaService.usedRatio(quotaStatus);
        ChatSession session = null;
        ChatSessionMemory sessionMemory = null;
        if (request.sessionId() != null) {
            session = requireSessionForUser(request.sessionId(), userId);
            sessionMemory = chatMapper.toResponse(session.memory());
        }
        UserPatientScopeService.PatientScope patientScope = userPatientScopeService.resolve(
                user,
                request.patientId(),
                sessionMemory
        );
        if (session == null) {
            session = chatSessionRepository.create(user, titleFromMessage(request.message()));
            sessionMemory = chatMapper.toResponse(session.memory());
        }
        UUID sessionId = session.getId();
        ChatSessionMemory scopedSessionMemory = userPatientScopeService.scopedMemory(sessionMemory, patientScope);
        String effectivePatientId = patientScope.effectivePatientId();
        List<ChatContextMessage> recentMessages = chatMapper.toContextMessages(
                chatSessionRepository.findRecentMessagesForContext(
                        sessionId,
                        userId,
                        RECENT_CONTEXT_MESSAGE_LIMIT
                )
        );
        // Đếm TRƯỚC khi lưu user message hiện tại (cùng thời điểm với recentMessages)
        // để chatbot-service quyết định trigger rolling summary nhất quán.
        int totalMessageCount = (int) chatMessageRepository.countBySession_Id(sessionId);
        ConversationContext conversationContext = conversationContext(
                scopedSessionMemory,
                recentMessages,
                totalMessageCount
        );

        chatMessageRepository.save(session, ChatMessageRole.USER, request.message(), chatbotResponseMapper.userMessageMetadata(
                request,
                effectivePatientId
        ));

        String llmKey = llmGatewayKeyService.resolveUserKey(user);

        long startedAtNanos = System.nanoTime();
        JsonNode chatbotResponse = chatbotServiceClient.chat(new ChatbotChatRequest(
                userId.toString(),
                user.getRole().name(),
                sessionId.toString(),
                request.message(),
                effectivePatientId,
                patientScope.allowedPatientIds(),
                patientScope.patientScope(),
                conversationContext,
                quotaUsedRatio,
                llmKey
        ));
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

        String answer = chatbotResponseMapper.answer(chatbotResponse);
        ChatMessage assistantMsg = chatMessageRepository.saveAndReturn(
                session,
                ChatMessageRole.ASSISTANT,
                answer,
                chatbotResponseMapper.assistantMessageMetadata(chatbotResponse)
        );
        ChatSessionMemory nextMemory = chatbotResponseMapper.nextSessionMemory(
                scopedSessionMemory,
                effectivePatientId,
                chatbotResponse
        );
        chatSessionRepository.updateMemory(session, chatMapper.toDomain(nextMemory));
        chatInteractionRecorder.record(user, session, request, effectivePatientId, chatbotResponse, latencyMs);

        return chatbotResponseMapper.toResponse(sessionId, assistantMsg.getId(), chatbotResponse);
    }

    public ChatSessionListResponse recentSessions(int limit) {
        return sessions(null, limit);
    }

    public ChatSessionListResponse sessions(String query, int limit) {
        UUID userId = currentUserService.requireCurrentUserId();
        String normalizedQuery = normalizeSessionSearchQuery(query);
        List<ChatSessionSummary> sessions = chatMapper.toSessionSummaries(normalizedQuery == null
                ? chatSessionRepository.findRecentSessionsForUser(userId, limit)
                : chatSessionRepository.searchSessionsForUser(userId, normalizedQuery, limit));
        return new ChatSessionListResponse(sessions);
    }

    public ChatMessagesResponse sessionMessages(UUID sessionId) {
        UUID userId = currentUserService.requireCurrentUserId();
        requireSessionForUser(sessionId, userId);
        List<ChatMessageItem> messages = chatMapper.toMessageItems(
                chatSessionRepository.findMessagesForSession(sessionId, userId)
        );
        return new ChatMessagesResponse(sessionId, messages);
    }

    @Transactional
    public ChatSessionRenameResponse renameSession(UUID sessionId, String title) {
        UUID userId = currentUserService.requireCurrentUserId();
        ChatSession session = chatSessionRepository.findByIdAndUser_Id(sessionId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiên trò chuyện."));
        String normalizedTitle = normalizeSessionTitle(title);
        session.setTitle(normalizedTitle);
        chatSessionRepository.save(session);
        return new ChatSessionRenameResponse(sessionId, normalizedTitle);
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        UUID userId = currentUserService.requireCurrentUserId();
        // Xóa phiên: chat_messages/message_feedback cascade theo FK; usage_logs/audit_logs
        // giữ lại và được set session_id = null (theo ràng buộc migration V1).
        long deleted = chatSessionRepository.deleteByIdAndUser_Id(sessionId, userId);
        if (deleted == 0) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiên trò chuyện.");
        }
    }

    private String normalizeSessionTitle(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Tiêu đề hội thoại không được để trống.");
        }
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }

    private ChatSession requireSessionForUser(UUID sessionId, UUID userId) {
        if (!chatSessionRepository.existsForUser(sessionId, userId)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiên trò chuyện.");
        }
        return chatSessionRepository.getReferenceById(sessionId);
    }

    private String normalizeSessionSearchQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > SESSION_SEARCH_QUERY_MAX_LENGTH) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Từ khóa tìm kiếm quá dài.");
        }
        return trimmed;
    }

    private ConversationContext conversationContext(
            ChatSessionMemory sessionMemory,
            List<ChatContextMessage> recentMessages,
            int totalMessageCount
    ) {
        return new ConversationContext(
                sessionMemory.memorySummary(),
                sessionMemory.activePatientId(),
                sessionMemory.lastIntent(),
                sessionMemory.lastToolName(),
                sessionMemory.lastResourceType(),
                sessionMemory.lastResourceId(),
                recentMessages,
                totalMessageCount
        );
    }

    private String titleFromMessage(String message) {
        String normalized = message == null ? "Cuộc trò chuyện mới" : message.strip();
        if (normalized.isEmpty()) {
            return "Cuộc trò chuyện mới";
        }
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }
}
