package com.medicalchatbot.backend.mapper;

import java.util.List;

import com.medicalchatbot.backend.dto.chatbot.ContextMessage;
import com.medicalchatbot.backend.dto.chatbot.ConversationContext;
import com.medicalchatbot.backend.dto.response.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.springframework.stereotype.Component;

@Component
public class ChatMapper {

    public ConversationContext toConversationContext(
            ChatMemory memory,
            List<ChatContextMessageProjection> recentMessages,
            int totalMessageCount
    ) {
        return new ConversationContext(
                memory.summary(),
                memory.activePatientId(),
                memory.lastIntent(),
                memory.lastToolName(),
                memory.lastResourceType(),
                memory.lastResourceId(),
                recentMessages.stream()
                        .map(message -> new ContextMessage(message.role(), message.content()))
                        .toList(),
                totalMessageCount
        );
    }

    public List<ChatSessionResponse> toSessionResponses(List<ChatSessionSummaryProjection> sessions) {
        return sessions.stream()
                .map(session -> new ChatSessionResponse(
                        session.id(),
                        session.title(),
                        session.createdAt(),
                        session.updatedAt(),
                        session.activePatientId(),
                        session.messageCount(),
                        session.lastMessagePreview()
                ))
                .toList();
    }

    public List<ChatMessageResponse> toMessageResponses(List<ChatMessageProjection> messages) {
        return messages.stream().map(ChatMapper::toMessageResponse).toList();
    }

    private static ChatMessageResponse toMessageResponse(ChatMessageProjection message) {
        ChatMessageResponse.Feedback feedback = message.feedbackRating() == null
                ? null
                : new ChatMessageResponse.Feedback(message.feedbackRating(), message.feedbackComment());
        ChatMessageResponse.Grounding grounding = message.groundingChecked() == null
                ? null
                : new ChatMessageResponse.Grounding(message.groundingChecked(), message.groundingPassed());
        return new ChatMessageResponse(
                message.id(),
                message.role(),
                message.content(),
                message.createdAt(),
                feedback,
                message.responseStatus(),
                message.traceId(),
                message.answerSource(),
                grounding,
                message.llmUnavailableReason()
        );
    }
}
