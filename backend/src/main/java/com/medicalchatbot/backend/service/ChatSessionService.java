package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.chat.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionResponse;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.util.SecurityUtils;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Chat history of the signed-in user: listing, reading, renaming and deleting conversations. */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatSessionService {

    private static final int SEARCH_QUERY_MAX_LENGTH = 100;
    private static final int TITLE_MAX_LENGTH = 255;

    ChatSessionRepository chatSessionRepository;
    ChatMapper chatMapper;

    /** Most recently active sessions; a blank {@code query} lists all of them. */
    @Transactional(readOnly = true)
    public List<ChatSessionResponse> list(String query, int limit) {
        String searchTerm = TextUtils.trimToNull(query);
        if (searchTerm != null && searchTerm.length() > SEARCH_QUERY_MAX_LENGTH) {
            throw new AppException(ErrorCode.SEARCH_QUERY_TOO_LONG);
        }
        UUID userId = SecurityUtils.currentUserId();
        return chatMapper.toSessionResponses(chatSessionRepository.searchSessions(userId, searchTerm, limit));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(UUID sessionId) {
        UUID userId = SecurityUtils.currentUserId();
        requireOwnedSession(sessionId, userId);
        return chatMapper.toMessageResponses(chatSessionRepository.findMessages(sessionId, userId));
    }

    @Transactional
    public ChatSessionRenameResponse rename(UUID sessionId, String title) {
        String newTitle = TextUtils.trimToNull(title);
        if (newTitle == null) {
            throw new AppException(ErrorCode.CHAT_SESSION_TITLE_BLANK);
        }
        ChatSession session = requireOwnedSession(sessionId, SecurityUtils.currentUserId());
        session.setTitle(TextUtils.truncate(newTitle, TITLE_MAX_LENGTH));
        return new ChatSessionRenameResponse(sessionId, session.getTitle());
    }

    /**
     * Deletes the session with its messages and feedback (FK cascade). Usage and audit rows are
     * kept with {@code session_id = null}, so quotas and the audit trail stay intact.
     */
    @Transactional
    public void delete(UUID sessionId) {
        if (chatSessionRepository.deleteByIdAndUserId(sessionId, SecurityUtils.currentUserId()) == 0) {
            throw sessionNotFound();
        }
    }

    /** A session of another user is reported exactly like a missing one. */
    public ChatSession requireOwnedSession(UUID sessionId, UUID userId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, userId).orElseThrow(ChatSessionService::sessionNotFound);
    }

    private static AppException sessionNotFound() {
        return new AppException(ErrorCode.CHAT_SESSION_NOT_EXISTED);
    }
}
