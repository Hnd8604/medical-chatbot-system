package com.medicalchatbot.backend.mapper;

import java.util.List;

import com.medicalchatbot.backend.domain.model.ChatSessionMemoryState;
import com.medicalchatbot.backend.dto.request.ChatContextMessage;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.springframework.stereotype.Component;

@Component
public class ChatMapper {

    public ChatSessionMemory toResponse(ChatSessionMemoryState memory) {
        if (memory == null) {
            return ChatSessionMemory.empty();
        }
        return new ChatSessionMemory(
                memory.activePatientId(),
                memory.memorySummary(),
                memory.lastIntent(),
                memory.lastToolName(),
                memory.lastResourceType(),
                memory.lastResourceId()
        );
    }

    public ChatSessionMemoryState toDomain(ChatSessionMemory memory) {
        if (memory == null) {
            return ChatSessionMemoryState.empty();
        }
        return new ChatSessionMemoryState(
                memory.activePatientId(),
                memory.memorySummary(),
                memory.lastIntent(),
                memory.lastToolName(),
                memory.lastResourceType(),
                memory.lastResourceId()
        );
    }

    public List<ChatContextMessage> toContextMessages(List<ChatContextMessageProjection> messages) {
        return messages.stream()
                .map(message -> new ChatContextMessage(message.role(), message.content()))
                .toList();
    }

    public List<ChatSessionSummary> toSessionSummaries(List<ChatSessionSummaryProjection> sessions) {
        return sessions.stream()
                .map(session -> new ChatSessionSummary(
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

    public List<ChatMessageItem> toMessageItems(List<ChatMessageProjection> messages) {
        return messages.stream()
                .map(message -> new ChatMessageItem(
                        message.id(),
                        message.role(),
                        message.content(),
                        message.createdAt(),
                        message.feedbackRating() == null
                                ? null
                                : new ChatMessageItem.Feedback(
                                        message.feedbackRating(),
                                        message.feedbackComment()
                                )
                ))
                .toList();
    }
}
