package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.MessageFeedback;
import com.medicalchatbot.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, UUID> {

    Optional<MessageFeedback> findByMessage_IdAndUser_Id(UUID messageId, UUID userId);

    default MessageFeedback save(ChatMessage message, User user, int rating, String comment) {
        return save(new MessageFeedback(message, user, rating, comment));
    }
}
