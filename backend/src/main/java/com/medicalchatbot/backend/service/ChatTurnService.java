package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.request.chat.ChatRequest;
import com.medicalchatbot.backend.dto.response.chat.ChatResponse;
import com.medicalchatbot.backend.dto.response.usage.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.PatientScopeService.PatientScope;
import com.medicalchatbot.backend.util.SecurityUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database steps of a chat turn. Each runs in its own short transaction, so no pooled
 * connection is held while {@link ChatService} waits for chatbot-service in between.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatTurnService {

    /** Messages of the session sent along as conversation context. */
    private static final int CONTEXT_MESSAGE_LIMIT = 8;
    /** Trần số tin gửi kèm khi còn tồn nhiều tin chưa tóm tắt (summary lỡ nhiều lượt liền). */
    private static final int MAX_SUMMARY_CONTEXT_MESSAGES = 24;
    private static final int TITLE_MAX_LENGTH = 80;
    private static final String DEFAULT_TITLE = "Cuộc trò chuyện mới";

    ChatSessionRepository chatSessionRepository;
    ChatMessageRepository chatMessageRepository;
    UserRepository userRepository;
    ChatSessionService chatSessionService;
    QuotaService quotaService;
    PatientScopeService patientScopeService;
    ChatInteractionRecorder interactionRecorder;
    AlertService alertService;
    ChatMapper chatMapper;
    ChatbotResponseMapper chatbotResponseMapper;

    /**
     * Checks quota and patient scope, opens a session when the request has none and stores the
     * question. Nothing is stored when a check fails.
     *
     * @return what the next steps need; its entities are detached once the transaction commits
     */
    @Transactional
    public PreparedTurn prepare(ChatRequest request) {
        User user = userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        QuotaStatusResponse quota = quotaService.assertQuotaAvailable(user);

        ChatSession existingSession = request.sessionId() == null
                ? null
                : chatSessionService.requireOwnedSession(request.sessionId(), user.getId());
        ChatMemory storedMemory = existingSession == null ? ChatMemory.EMPTY : existingSession.getMemory();
        PatientScope scope = patientScopeService.resolve(user, request.patientId(), storedMemory);
        ChatSession session = existingSession != null ? existingSession : startSession(user, request.message());
        ChatMemory memory = scope.restrict(storedMemory);

        // Counted before the current question is stored, so chatbot-service sees the same history
        // size as the recent messages it receives.
        int previousMessageCount = (int) chatMessageRepository.countBySessionId(session.getId());
        ChatbotChatRequest chatbotRequest = buildChatbotRequest(
                user, session, request, scope, memory, quota, previousMessageCount);
        ChatMessage question = saveMessage(session, ChatMessageRole.USER, request.message(),
                chatbotResponseMapper.userMessageMetadata(request.patientId(), scope.effectivePatientId()));
        return new PreparedTurn(user, existingSession, session.getId(), question.getId(), request.patientId(),
                scope, memory, chatbotRequest, previousMessageCount);
    }

    /** Stores the answer, the next session memory, the usage and the audit entry of the turn. */
    @Transactional
    public ChatResponse complete(PreparedTurn turn, ChatbotChatResponse reply, long latencyMs) {
        // Reloaded: the session of the prepare step is detached, and the user may have deleted it meanwhile.
        ChatSession session = chatSessionService.requireOwnedSession(turn.sessionId(), turn.user().getId());
        String effectivePatientId = turn.scope().effectivePatientId();

        ChatMessage answer = saveMessage(session, ChatMessageRole.ASSISTANT, reply.answer(),
                chatbotResponseMapper.assistantMessageMetadata(reply));
        if (chatbotResponseMapper.hasSummaryUpdate(reply)) {
            // Summary mới gộp mọi tin có trước lượt này (+ câu hỏi hiện tại).
            session.markSummarizedThrough(turn.previousMessageCount());
        }
        session.updateMemory(chatbotResponseMapper.nextMemory(turn.memory(), effectivePatientId, reply));
        interactionRecorder.recordCompletedTurn(
                turn.user(), session, turn.requestPatientId(), effectivePatientId, reply, latencyMs);

        // chatbot-service answers HTTP 200 even when every retrieval failed, so no exception reports it.
        if (reply.hasToolError()) {
            alertService.raise(AlertType.CHATBOT_TOOL_ERROR, "Dịch vụ AI không truy xuất được dữ liệu FHIR.",
                    chatbotResponseMapper.toolErrorAlertMetadata(reply));
        }
        return chatbotResponseMapper.toResponse(session.getId(), answer.getId(), reply);
    }

    /**
     * Undoes {@link #prepare} after a failed turn, so the turn leaves no trace in the history: a
     * session the turn opened is deleted with its question, otherwise only the question is.
     */
    @Transactional
    public void discard(PreparedTurn turn) {
        if (turn.opensSession()) {
            chatSessionRepository.deleteByIdAndUserId(turn.sessionId(), turn.user().getId());
        } else {
            chatMessageRepository.deleteById(turn.questionId());
        }
    }

    private ChatbotChatRequest buildChatbotRequest(
            User user,
            ChatSession session,
            ChatRequest request,
            PatientScope scope,
            ChatMemory memory,
            QuotaStatusResponse quota,
            int previousMessageCount
    ) {
        int summarizedMessageCount = summarizedMessageCount(session, memory, previousMessageCount);
        int contextLimit = contextMessageLimit(previousMessageCount, summarizedMessageCount);
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
                        chatSessionRepository.findRecentMessages(session.getId(), user.getId(), contextLimit),
                        previousMessageCount,
                        summarizedMessageCount
                ),
                quotaService.usedRatio(quota)
        );
    }

    /**
     * Mốc summary gửi cho chatbot-service. Không có summary (hoặc summary bị ẩn do đổi phạm vi
     * bệnh nhân của USER) thì mốc = 0: chatbot phải tóm tắt lại từ các tin hiện có.
     */
    private static int summarizedMessageCount(ChatSession session, ChatMemory memory, int previousMessageCount) {
        if (memory.summary() == null || memory.summary().isBlank()) {
            return 0;
        }
        return Math.min(Math.max(session.getMemorySummaryMessageCount(), 0), previousMessageCount);
    }

    /** Luôn gửi ít nhất cửa sổ thường; còn tồn tin chưa tóm tắt thì gửi đủ các tin đó (có trần). */
    private static int contextMessageLimit(int previousMessageCount, int summarizedMessageCount) {
        int unsummarized = previousMessageCount - summarizedMessageCount;
        return Math.max(CONTEXT_MESSAGE_LIMIT, Math.min(unsummarized, MAX_SUMMARY_CONTEXT_MESSAGES));
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

    /**
     * A turn whose question is stored and whose request to chatbot-service is ready.
     *
     * @param existingSession session that existed before the turn, or null when the turn opened one
     * @param sessionId       session of the turn, existing or just opened
     * @param questionId      the stored question
     * @param previousMessageCount messages of the session before the question
     */
    public record PreparedTurn(
            User user,
            ChatSession existingSession,
            UUID sessionId,
            UUID questionId,
            String requestPatientId,
            PatientScope scope,
            ChatMemory memory,
            ChatbotChatRequest chatbotRequest,
            int previousMessageCount
    ) {

        boolean opensSession() {
            return existingSession == null;
        }
    }
}
