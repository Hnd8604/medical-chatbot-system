package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    long countBySession_Id(UUID sessionId);

    @Query("""
            select m
            from ChatMessage m
            join m.session s
            where m.id = :messageId
              and s.user.id = :userId
              and m.role = 'assistant'
            """)
    Optional<ChatMessage> findAssistantMessageOwnedByUser(
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId
    );

    default void save(ChatSession session, ChatMessageRole role, String content) {
        save(session, role, content, null);
    }

    default void save(ChatSession session, ChatMessageRole role, String content, JsonNode metadata) {
        session.touch();
        save(new ChatMessage(session, role.databaseValue(), content, metadata));
    }

    default ChatMessage saveAndReturn(ChatSession session, ChatMessageRole role, String content, JsonNode metadata) {
        session.touch();
        return save(new ChatMessage(session, role.databaseValue(), content, metadata));
    }
}
