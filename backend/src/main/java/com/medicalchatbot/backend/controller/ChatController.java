package com.medicalchatbot.backend.controller;

import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.chat.ChatRequest;
import com.medicalchatbot.backend.dto.request.chat.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatResponse;
import com.medicalchatbot.backend.dto.response.chat.FeedbackResponse;
import com.medicalchatbot.backend.service.ChatService;
import com.medicalchatbot.backend.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Chat", description = "Asking the medical assistant and rating its answers")
public class ChatController {

    ChatService chatService;
    FeedbackService feedbackService;

    @PostMapping
    @Operation(summary = "Ask a question",
            description = "Sends one message to the assistant; omit sessionId to start a new conversation. "
                    + "Subject to the daily quota and to a per-user rate limit.")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.<ChatResponse>builder()
                .result(chatService.chat(request))
                .message(ResponseMessage.CHAT_SUCCESS)
                .build();
    }

    @PostMapping("/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Rate an answer", description = "Rates an assistant answer of one's own conversations (1-5 stars), once.")
    public ApiResponse<FeedbackResponse> createFeedback(
            @PathVariable UUID messageId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return ApiResponse.<FeedbackResponse>builder()
                .result(feedbackService.create(messageId, request))
                .message(ResponseMessage.CREATE_FEEDBACK_SUCCESS)
                .build();
    }

    @PutMapping("/messages/{messageId}/feedback")
    @Operation(summary = "Change a rating", description = "Replaces the rating and comment of an answer.")
    public ApiResponse<FeedbackResponse> updateFeedback(
            @PathVariable UUID messageId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return ApiResponse.<FeedbackResponse>builder()
                .result(feedbackService.update(messageId, request))
                .message(ResponseMessage.UPDATE_FEEDBACK_SUCCESS)
                .build();
    }

    @DeleteMapping("/messages/{messageId}/feedback")
    @Operation(summary = "Remove a rating", description = "Deletes the rating of an answer.")
    public ApiResponse<Void> deleteFeedback(@PathVariable UUID messageId) {
        feedbackService.delete(messageId);
        return ApiResponse.<Void>builder().message(ResponseMessage.DELETE_FEEDBACK_SUCCESS).build();
    }
}
