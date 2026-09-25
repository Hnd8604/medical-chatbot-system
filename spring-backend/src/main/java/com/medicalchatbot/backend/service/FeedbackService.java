package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.MessageFeedback;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.MessageFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final MessageFeedbackRepository feedbackRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public FeedbackResponse createFeedback(UUID messageId, FeedbackRequest request) {
        User user = currentUserService.requireCurrentUser();
        ChatMessage message = chatMessageRepository
                .findAssistantMessageOwnedByUser(messageId, user.getId())
                .orElseThrow(FeedbackService::messageNotFound);

        feedbackRepository.findByMessage_IdAndUser_Id(messageId, user.getId())
                .ifPresent(existing -> {
                    throw feedbackAlreadyExists();
                });

        try {
            MessageFeedback feedback = feedbackRepository.saveAndFlush(
                    new MessageFeedback(message, user, request.rating(), request.comment())
            );
            return toResponse(feedback, messageId);
        } catch (DataIntegrityViolationException ex) {
            // The database unique constraint closes the race between the existence check and insert.
            throw feedbackAlreadyExists(ex);
        }
    }

    @Transactional
    public FeedbackResponse updateFeedback(UUID messageId, FeedbackRequest request) {
        User user = currentUserService.requireCurrentUser();
        MessageFeedback feedback = feedbackRepository
                .findAuthorizedFeedback(messageId, user.getId())
                .orElseThrow(FeedbackService::feedbackNotFound);

        feedback.update(request.rating(), request.comment());
        return toResponse(feedback, messageId);
    }

    @Transactional
    public void deleteFeedback(UUID messageId) {
        User user = currentUserService.requireCurrentUser();
        MessageFeedback feedback = feedbackRepository
                .findAuthorizedFeedback(messageId, user.getId())
                .orElseThrow(FeedbackService::feedbackNotFound);

        feedbackRepository.delete(feedback);
    }

    private FeedbackResponse toResponse(MessageFeedback feedback, UUID messageId) {
        return new FeedbackResponse(
                feedback.getId(),
                messageId,
                feedback.getRating(),
                feedback.getComment()
        );
    }

    private static AppException messageNotFound() {
        return new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Message not found.");
    }

    private static AppException feedbackNotFound() {
        return new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Feedback not found.");
    }

    private static AppException feedbackAlreadyExists() {
        return feedbackAlreadyExists(null);
    }

    private static AppException feedbackAlreadyExists(Throwable cause) {
        return new AppException(
                ErrorCode.CONFLICT,
                "Feedback already exists for this message.",
                cause
        );
    }
}
