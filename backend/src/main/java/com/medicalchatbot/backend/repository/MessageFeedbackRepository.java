package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.MessageFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, UUID> {

    Optional<MessageFeedback> findByMessage_IdAndUser_Id(UUID messageId, UUID userId);

    @Query("""
            select f
            from MessageFeedback f
            join f.message m
            join m.session s
            where m.id = :messageId
              and f.user.id = :userId
              and s.user.id = :userId
              and m.role = 'assistant'
            """)
    Optional<MessageFeedback> findAuthorizedFeedback(
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId
    );
}
