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

import com.medicalchatbot.backend.dto.request.chat.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.chat.FeedbackResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.MessageFeedback;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.MessageFeedbackRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageFeedbackRepository feedbackRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private FeedbackService service;
    private User user;
    private UUID messageId;
    private ChatMessage message;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, chatMessageRepository, userRepository);
        user = TestUsers.user(UserRole.USER);
        messageId = UUID.randomUUID();
        message = ChatMessage.builder().id(messageId).role(ChatMessageRole.ASSISTANT).content("answer").build();
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void createAllowsAnOwnedAssistantMessage() {
        UUID feedbackId = UUID.randomUUID();
        TestUsers.signIn(user, userRepository);
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, user.getId())).thenReturn(Optional.of(message));
        when(feedbackRepository.existsByMessageIdAndUserId(messageId, user.getId())).thenReturn(false);
        when(feedbackRepository.saveAndFlush(any(MessageFeedback.class))).thenAnswer(invocation -> {
            MessageFeedback saved = invocation.getArgument(0);
            saved.setId(feedbackId);
            return saved;
        });

        FeedbackResponse response = service.create(messageId, new FeedbackRequest(5, "Helpful"));

        assertThat(response).isEqualTo(new FeedbackResponse(feedbackId, messageId, 5, "Helpful"));
    }

    @Test
    void createDoesNotRevealMissingNonAssistantOrForeignMessage() {
        TestUsers.signIn(user, userRepository);
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, user.getId())).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> service.create(messageId, new FeedbackRequest(4, null)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MESSAGE_NOT_EXISTED);
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    void createRejectsExistingFeedback() {
        TestUsers.signIn(user, userRepository);
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, user.getId())).thenReturn(Optional.of(message));
        when(feedbackRepository.existsByMessageIdAndUserId(messageId, user.getId())).thenReturn(true);

        AppException exception = assertThrows(AppException.class,
                () -> service.create(messageId, new FeedbackRequest(4, null)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_EXISTED);
        verify(feedbackRepository, never()).saveAndFlush(any(MessageFeedback.class));
    }

    @Test
    void createMapsConcurrentDuplicateToConflict() {
        TestUsers.signIn(user, userRepository);
        when(chatMessageRepository.findAssistantMessageOwnedByUser(messageId, user.getId())).thenReturn(Optional.of(message));
        when(feedbackRepository.existsByMessageIdAndUserId(messageId, user.getId())).thenReturn(false);
        when(feedbackRepository.saveAndFlush(any(MessageFeedback.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        AppException exception = assertThrows(AppException.class,
                () -> service.create(messageId, new FeedbackRequest(4, null)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_EXISTED);
    }

    @Test
    void updateUsesTheOwnershipAwareLookup() {
        MessageFeedback feedback = MessageFeedback.builder().id(UUID.randomUUID()).message(message).user(user)
                .rating(2).comment("Old").build();
        TestUsers.signIn(user);
        when(feedbackRepository.findOwnedFeedback(messageId, user.getId())).thenReturn(Optional.of(feedback));

        FeedbackResponse response = service.update(messageId, new FeedbackRequest(5, "Updated"));

        assertThat(feedback.getRating()).isEqualTo(5);
        assertThat(feedback.getComment()).isEqualTo("Updated");
        assertThat(response.rating()).isEqualTo(5);
    }

    @Test
    void updateDoesNotRevealForeignFeedback() {
        TestUsers.signIn(user);
        when(feedbackRepository.findOwnedFeedback(messageId, user.getId())).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> service.update(messageId, new FeedbackRequest(5, null)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_NOT_EXISTED);
    }

    @Test
    void deleteUsesTheOwnershipAwareLookup() {
        MessageFeedback feedback = MessageFeedback.builder().message(message).user(user).rating(5).build();
        TestUsers.signIn(user);
        when(feedbackRepository.findOwnedFeedback(messageId, user.getId())).thenReturn(Optional.of(feedback));

        service.delete(messageId);

        verify(feedbackRepository).delete(feedback);
    }
}
