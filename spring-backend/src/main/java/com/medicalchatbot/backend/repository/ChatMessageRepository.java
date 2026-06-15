package com.medicalchatbot.backend.repository;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

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
