package com.medicalchatbot.backend.controller;

import java.util.UUID;

import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.service.ChatService;
import com.medicalchatbot.backend.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Sending chat messages and rating the answers. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final FeedbackService feedbackService;

    /** Rate limited per user (see {@code RateLimitInterceptor}). */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse createFeedback(@PathVariable UUID messageId, @Valid @RequestBody FeedbackRequest request) {
        return feedbackService.create(messageId, request);
    }

    @PutMapping("/messages/{messageId}/feedback")
    public FeedbackResponse updateFeedback(@PathVariable UUID messageId, @Valid @RequestBody FeedbackRequest request) {
        return feedbackService.update(messageId, request);
    }

    @DeleteMapping("/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFeedback(@PathVariable UUID messageId) {
        feedbackService.delete(messageId);
    }
}
