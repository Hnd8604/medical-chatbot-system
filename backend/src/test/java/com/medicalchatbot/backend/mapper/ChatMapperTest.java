package com.medicalchatbot.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.chatbot.ConversationContext;
import com.medicalchatbot.backend.dto.response.chat.ChatMessageResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class ChatMapperTest {

    private final ChatMapper mapper = Mappers.getMapper(ChatMapper.class);

    @Test
    void buildsConversationContextFromMemoryAndRecentMessages() {
        ChatMemory memory = new ChatMemory("patient-1", "summary", "intent", "tool", "Observation", "observation-1");

        ConversationContext context = mapper.toConversationContext(
                memory, List.of(new ChatContextMessageProjection("user", "hello")), 4, 2);

        assertEquals("summary", context.memorySummary());
        assertEquals("patient-1", context.activePatientId());
        assertEquals("observation-1", context.lastResourceId());
        assertEquals("hello", context.recentMessages().getFirst().content());
        assertEquals(4, context.totalMessageCount());
        assertEquals(2, context.summarizedMessageCount());
    }

    @Test
    void mapsRepositoryChatProjectionsToApiModels() {
        UUID sessionId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-09-25T08:00:00Z");

        var sessions = mapper.toSessionResponses(List.of(
                new ChatSessionSummaryProjection(sessionId, "Session", timestamp, timestamp, "patient-1", 1, "hello")
        ));
        var messages = mapper.toMessageResponses(List.of(
                new ChatMessageProjection(messageId, "assistant", "answer", timestamp, 5, "helpful",
                        "partial", "trace-1", "llm", true, false, null),
                new ChatMessageProjection(UUID.randomUUID(), "user", "question", timestamp, null, null,
                        null, null, null, null, null, null)
        ));

        assertEquals(sessionId, sessions.getFirst().id());
        assertEquals(5, messages.getFirst().feedback().rating());
        assertNull(messages.get(1).feedback());
        assertEquals("partial", messages.getFirst().responseStatus());
        assertEquals("trace-1", messages.getFirst().traceId());
        assertEquals(new ChatMessageResponse.Grounding(true, false), messages.getFirst().grounding());
        // A message without a stored grounding result (user message, older answer) has no grounding object.
        assertNull(messages.get(1).grounding());
        assertNull(messages.get(1).responseStatus());
    }
}
