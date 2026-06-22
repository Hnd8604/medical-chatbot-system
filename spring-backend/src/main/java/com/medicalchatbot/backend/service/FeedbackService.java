package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.MessageFeedback;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.MessageFeedbackRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeedbackService {

    private final MessageFeedbackRepository feedbackRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CurrentUserService currentUserService;

    public FeedbackService(
            MessageFeedbackRepository feedbackRepository,
            ChatMessageRepository chatMessageRepository,
            CurrentUserService currentUserService
    ) {
        this.feedbackRepository = feedbackRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public FeedbackResponse submitFeedback(UUID messageId, FeedbackRequest request) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found."));

        User user = currentUserService.requireCurrentUser();
        MessageFeedback feedback = feedbackRepository.save(message, user, request.rating(), request.comment());

        return new FeedbackResponse(
                feedback.getId(),
                messageId,
                feedback.getRating(),
                feedback.getComment()
        );
    }
}
