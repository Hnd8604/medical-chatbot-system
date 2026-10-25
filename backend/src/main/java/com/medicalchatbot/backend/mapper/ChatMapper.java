package com.medicalchatbot.backend.mapper;

import java.util.List;

import com.medicalchatbot.backend.dto.chatbot.ContextMessage;
import com.medicalchatbot.backend.dto.chatbot.ConversationContext;
import com.medicalchatbot.backend.dto.response.chat.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ChatMapper {

    @Mapping(target = "memorySummary", source = "memory.summary")
    ConversationContext toConversationContext(
            ChatMemory memory,
            List<ChatContextMessageProjection> recentMessages,
            int totalMessageCount,
            int summarizedMessageCount
    );

    ContextMessage toContextMessage(ChatContextMessageProjection message);

    List<ChatSessionResponse> toSessionResponses(List<ChatSessionSummaryProjection> sessions);

    ChatSessionResponse toSessionResponse(ChatSessionSummaryProjection session);

    List<ChatMessageResponse> toMessageResponses(List<ChatMessageProjection> messages);

    @Mapping(target = "feedback", source = ".")
    @Mapping(target = "grounding", source = ".")
    ChatMessageResponse toMessageResponse(ChatMessageProjection message);

    /** Only messages that were rated carry feedback. */
    default ChatMessageResponse.Feedback toFeedback(ChatMessageProjection message) {
        return message.feedbackRating() == null
                ? null
                : new ChatMessageResponse.Feedback(message.feedbackRating(), message.feedbackComment());
    }

    /** Only answers whose grounding was checked carry the result. */
    default ChatMessageResponse.Grounding toGrounding(ChatMessageProjection message) {
        return message.groundingChecked() == null
                ? null
                : new ChatMessageResponse.Grounding(message.groundingChecked(), message.groundingPassed());
    }
}
