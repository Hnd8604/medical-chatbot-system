package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    long countBySessionId(UUID sessionId);

    /** An assistant message in one of the user's own sessions — the only messages that can be rated. */
    @Query("""
            select m
            from ChatMessage m
            join m.session s
            where m.id = :messageId
              and s.user.id = :userId
              and m.role = com.medicalchatbot.backend.enums.ChatMessageRole.ASSISTANT
            """)
    Optional<ChatMessage> findAssistantMessageOwnedByUser(
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId
    );
}
