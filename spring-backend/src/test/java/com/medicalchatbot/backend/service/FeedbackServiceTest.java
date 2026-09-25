package com.medicalchatbot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.MessageFeedback;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.MessageFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private MessageFeedbackRepository feedbackRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private CurrentUserService currentUserService;

    private FeedbackService feedbackService;
    private User user;
    private UUID userId;
    private UUID messageId;
    private ChatMessage message;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(feedbackRepository, chatMessageRepository, currentUserService);
        userId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        user = User.builder().id(userId).username("owner").build();
        message = ChatMessage.builder().id(messageId).role("assistant").build();
        when(currentUserService.requireCurrentUser()).thenReturn(user);
    }

    @Test
    void createFeedbackAllowsOwnedAssistantMessage() {
        UUID feedbackId = UUID.randomUUID();
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, userId))
                .thenReturn(Optional.of(message));
        when(feedbackRepository.findByMessage_IdAndUser_Id(messageId, userId))
                .thenReturn(Optional.empty());
        when(feedbackRepository.saveAndFlush(any(MessageFeedback.class))).thenAnswer(invocation -> {
            MessageFeedback saved = invocation.getArgument(0);
            saved.setId(feedbackId);
            return saved;
        });

        FeedbackResponse response = feedbackService.createFeedback(
                messageId,
                new FeedbackRequest(5, "Helpful")
        );

        assertThat(response.id()).isEqualTo(feedbackId);
        assertThat(response.messageId()).isEqualTo(messageId);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Helpful");
    }

    @Test
    void createFeedbackDoesNotRevealMissingNonAssistantOrForeignMessage() {
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, userId))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> feedbackService.createFeedback(messageId, new FeedbackRequest(4, null))
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    void createFeedbackRejectsExistingFeedback() {
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, userId))
                .thenReturn(Optional.of(message));
        when(feedbackRepository.findByMessage_IdAndUser_Id(messageId, userId))
                .thenReturn(Optional.of(new MessageFeedback(message, user, 3, null)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> feedbackService.createFeedback(messageId, new FeedbackRequest(4, null))
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(feedbackRepository, never()).saveAndFlush(any(MessageFeedback.class));
    }

    @Test
    void createFeedbackMapsConcurrentDuplicateToConflict() {
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, userId))
                .thenReturn(Optional.of(message));
        when(feedbackRepository.findByMessage_IdAndUser_Id(messageId, userId))
                .thenReturn(Optional.empty());
        when(feedbackRepository.saveAndFlush(any(MessageFeedback.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> feedbackService.createFeedback(messageId, new FeedbackRequest(4, null))
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateFeedbackUsesOwnershipAwareLookup() {
        MessageFeedback feedback = MessageFeedback.builder()
                .id(UUID.randomUUID())
                .message(message)
                .user(user)
                .rating(2)
                .comment("Old")
                .build();
        when(feedbackRepository.findAuthorizedFeedback(messageId, userId))
                .thenReturn(Optional.of(feedback));

        FeedbackResponse response = feedbackService.updateFeedback(
                messageId,
                new FeedbackRequest(5, "Updated")
        );

        assertThat(feedback.getRating()).isEqualTo(5);
        assertThat(feedback.getComment()).isEqualTo("Updated");
        assertThat(response.rating()).isEqualTo(5);
    }

    @Test
    void updateFeedbackDoesNotRevealForeignFeedback() {
        when(feedbackRepository.findAuthorizedFeedback(messageId, userId))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> feedbackService.updateFeedback(messageId, new FeedbackRequest(5, null))
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteFeedbackUsesOwnershipAwareLookup() {
        MessageFeedback feedback = new MessageFeedback(message, user, 5, null);
        when(feedbackRepository.findAuthorizedFeedback(messageId, userId))
                .thenReturn(Optional.of(feedback));

        feedbackService.deleteFeedback(messageId);

        verify(feedbackRepository).delete(feedback);
    }
}
