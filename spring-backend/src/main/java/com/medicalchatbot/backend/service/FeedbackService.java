package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.MessageFeedback;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.MessageFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final MessageFeedbackRepository feedbackRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public FeedbackResponse createFeedback(UUID messageId, FeedbackRequest request) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found."));

        User user = currentUserService.requireCurrentUser();
        feedbackRepository.findByMessage_IdAndUser_Id(messageId, user.getId())
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Feedback already exists for this message.");
                });

        MessageFeedback feedback = feedbackRepository.save(message, user, request.rating(), request.comment());
        return toResponse(feedback, messageId);
    }

    @Transactional
    public FeedbackResponse updateFeedback(UUID messageId, FeedbackRequest request) {
        User user = currentUserService.requireCurrentUser();
        MessageFeedback feedback = feedbackRepository
                .findByMessage_IdAndUser_Id(messageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Feedback not found for this message."));

        feedback.update(request.rating(), request.comment());
        return toResponse(feedback, messageId);
    }

    @Transactional
    public void deleteFeedback(UUID messageId) {
        User user = currentUserService.requireCurrentUser();
        MessageFeedback feedback = feedbackRepository
                .findByMessage_IdAndUser_Id(messageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Feedback not found for this message."));

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
}
