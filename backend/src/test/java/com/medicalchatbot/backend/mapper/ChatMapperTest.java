package com.medicalchatbot.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.domain.model.ChatSessionMemoryState;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.junit.jupiter.api.Test;

class ChatMapperTest {

    private final ChatMapper mapper = new ChatMapper();

    @Test
    void mapsDomainMemoryToApiModelAndBack() {
        ChatSessionMemoryState state = new ChatSessionMemoryState(
                "patient-1",
                "summary",
                "intent",
                "tool",
                "Observation",
                "observation-1"
        );

        ChatSessionMemory response = mapper.toResponse(state);

        assertEquals("patient-1", response.activePatientId());
        assertEquals(state, mapper.toDomain(response));
    }

    @Test
    void mapsRepositoryChatProjectionsToApiModels() {
        UUID sessionId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-09-25T08:00:00Z");

        var context = mapper.toContextMessages(List.of(
                new ChatContextMessageProjection("user", "hello")
        ));
        var sessions = mapper.toSessionSummaries(List.of(
                new ChatSessionSummaryProjection(
                        sessionId,
                        "Session",
                        timestamp,
                        timestamp,
                        "patient-1",
                        1,
                        "hello"
                )
        ));
        var messages = mapper.toMessageItems(List.of(
                new ChatMessageProjection(messageId, "assistant", "answer", timestamp, 5, "helpful",
                        "partial", "trace-1", "llm", true, false, null),
                new ChatMessageProjection(UUID.randomUUID(), "user", "question", timestamp, null, null,
                        null, null, null, null, null, null)
        ));

        assertEquals("hello", context.getFirst().content());
        assertEquals(sessionId, sessions.getFirst().id());
        assertEquals(5, messages.getFirst().feedback().rating());
        assertNull(messages.get(1).feedback());
        assertEquals("partial", messages.getFirst().responseStatus());
        assertEquals("trace-1", messages.getFirst().traceId());
        assertEquals(new ChatMessageItem.Grounding(true, false), messages.getFirst().grounding());
        // Message không lưu kết quả grounding (user, hoặc assistant cũ) thì không có object grounding.
        assertNull(messages.get(1).grounding());
        assertNull(messages.get(1).responseStatus());
    }
}
