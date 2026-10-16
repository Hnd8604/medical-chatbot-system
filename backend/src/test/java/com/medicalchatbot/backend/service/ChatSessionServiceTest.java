package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.ChatSessionResponse;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private CurrentUserService currentUserService;

    private ChatSessionService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(chatSessionRepository, currentUserService, new ChatMapper());
    }

    @Test
    void blankQueryListsRecentSessions() {
        ChatSessionSummaryProjection summary = new ChatSessionSummaryProjection(UUID.randomUUID(), "Recent chat",
                OffsetDateTime.parse("2026-06-01T10:00:00Z"), OffsetDateTime.parse("2026-06-01T10:05:00Z"),
                "BN2026-00001", 2, "Latest answer");
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.searchSessions(userId, null, 20)).thenReturn(List.of(summary));

        List<ChatSessionResponse> sessions = service.list("   ", 20);

        assertEquals(List.of(new ChatSessionResponse(summary.id(), summary.title(), summary.createdAt(),
                summary.updatedAt(), summary.activePatientId(), summary.messageCount(), summary.lastMessagePreview())),
                sessions);
    }

    @Test
    void queryIsTrimmedBeforeSearching() {
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.searchSessions(userId, "thuoc", 30)).thenReturn(List.of());

        service.list("  thuoc  ", 30);

        verify(chatSessionRepository).searchSessions(userId, "thuoc", 30);
    }

    @Test
    void overlongQueryIsRejected() {
        AppException exception = assertThrows(AppException.class, () -> service.list("x".repeat(101), 20));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }

    @Test
    void messagesOfAForeignSessionAreNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> service.messages(sessionId));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        verify(chatSessionRepository, never()).findMessages(any(), any());
    }

    @Test
    void renameTrimsAndTruncatesTheTitle() {
        ChatSession session = ChatSession.builder().id(UUID.randomUUID()).title("old").build();
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        String title = service.rename(session.getId(), "  " + "t".repeat(300) + "  ").title();

        assertEquals(255, title.length());
        assertEquals(title, session.getTitle());
    }

    @Test
    void blankTitleIsRejected() {
        AppException exception = assertThrows(AppException.class, () -> service.rename(UUID.randomUUID(), "   "));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }

    @Test
    void deletingAForeignSessionIsNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(chatSessionRepository.deleteByIdAndUserId(sessionId, userId)).thenReturn(0L);

        AppException exception = assertThrows(AppException.class, () -> service.delete(sessionId));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }
}
