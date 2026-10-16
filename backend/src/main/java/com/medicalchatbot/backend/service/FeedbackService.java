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

/** Star ratings on assistant answers; a user rates an answer of their own sessions at most once. */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final MessageFeedbackRepository feedbackRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public FeedbackResponse create(UUID messageId, FeedbackRequest request) {
        User user = currentUserService.requireCurrentUser();
        ChatMessage message = chatMessageRepository.findAssistantMessageOwnedByUser(messageId, user.getId())
                .orElseThrow(() -> AppException.notFound("Không tìm thấy tin nhắn."));
        if (feedbackRepository.existsByMessageIdAndUserId(messageId, user.getId())) {
            throw feedbackAlreadyExists(null);
        }

        try {
            MessageFeedback feedback = feedbackRepository.saveAndFlush(MessageFeedback.builder()
                    .message(message)
                    .user(user)
                    .rating(request.rating())
                    .comment(request.comment())
                    .build());
            return toResponse(feedback, messageId);
        } catch (DataIntegrityViolationException exception) {
            // The unique constraint closes the race between the existence check and the insert.
            throw feedbackAlreadyExists(exception);
        }
    }

    @Transactional
    public FeedbackResponse update(UUID messageId, FeedbackRequest request) {
        MessageFeedback feedback = requireOwnFeedback(messageId);
        feedback.setRating(request.rating());
        feedback.setComment(request.comment());
        return toResponse(feedback, messageId);
    }

    @Transactional
    public void delete(UUID messageId) {
        feedbackRepository.delete(requireOwnFeedback(messageId));
    }

    private MessageFeedback requireOwnFeedback(UUID messageId) {
        return feedbackRepository.findOwnedFeedback(messageId, currentUserService.requireCurrentUserId())
                .orElseThrow(() -> AppException.notFound("Không tìm thấy đánh giá."));
    }

    private static FeedbackResponse toResponse(MessageFeedback feedback, UUID messageId) {
        return new FeedbackResponse(feedback.getId(), messageId, feedback.getRating(), feedback.getComment());
    }

    private static AppException feedbackAlreadyExists(Throwable cause) {
        return new AppException(ErrorCode.CONFLICT, "Câu trả lời này đã được đánh giá.", cause);
    }
}
