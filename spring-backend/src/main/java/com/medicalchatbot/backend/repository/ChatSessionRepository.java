package com.medicalchatbot.backend.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.ChatContextMessage;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    java.util.Optional<ChatSession> findByIdAndUser_Id(UUID id, UUID userId);

    boolean existsByIdAndUser_Id(UUID id, UUID userId);

    default ChatSession create(User user, String title) {
        return save(new ChatSession(user, title));
    }

    default boolean existsForUser(UUID sessionId, UUID userId) {
        return existsByIdAndUser_Id(sessionId, userId);
    }

    default ChatSessionMemory findMemoryForSession(UUID sessionId, UUID userId) {
        return findByIdAndUser_Id(sessionId, userId)
                .map(ChatSession::memory)
                .orElseGet(ChatSessionMemory::empty);
    }

    default void updateMemory(ChatSession session, ChatSessionMemory memory) {
        session.applyMemory(memory);
        save(session);
    }

    @Query(
            value = """
                    select recent.role as "role", recent.content as "content"
                    from (
                        select
                            m.role,
                            m.content,
                            m.created_at as "createdAt"
                        from chat_messages m
                        join chat_sessions s on s.id = m.session_id
                        where s.id = :sessionId and s.user_id = :userId
                        order by m.created_at desc
                        limit :limit
                    ) recent
                    order by recent."createdAt" asc
                    """,
            nativeQuery = true
    )
    List<ChatContextMessageView> findRecentMessageViewsForContext(
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );

    default List<ChatContextMessage> findRecentMessagesForContext(UUID sessionId, UUID userId, int limit) {
        return findRecentMessageViewsForContext(sessionId, userId, limit)
                .stream()
                .map(message -> new ChatContextMessage(message.getRole(), message.getContent()))
                .toList();
    }

    @Query(
            value = """
                    select
                        s.id as "id",
                        s.title as "title",
                        s.created_at as "createdAt",
                        s.updated_at as "updatedAt",
                        s.active_patient_id as "activePatientId",
                        coalesce(message_counts.message_count, 0) as "messageCount",
                        left(coalesce(last_message.content, ''), 160) as "lastMessagePreview"
                    from chat_sessions s
                    left join lateral (
                        select count(*)::int as message_count
                        from chat_messages m
                        where m.session_id = s.id
                    ) message_counts on true
                    left join lateral (
                        select m.content
                        from chat_messages m
                        where m.session_id = s.id
                        order by m.created_at desc
                        limit 1
                    ) last_message on true
                    where s.user_id = :userId
                    order by s.updated_at desc
                    limit :limit
                    """,
            nativeQuery = true
    )
    List<ChatSessionSummaryView> findRecentSessionViewsForUser(
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );

    default List<ChatSessionSummary> findRecentSessionsForUser(UUID userId, int limit) {
        return findRecentSessionViewsForUser(userId, limit)
                .stream()
                .map(session -> new ChatSessionSummary(
                        session.getId(),
                        session.getTitle(),
                        toOffsetDateTime(session.getCreatedAt()),
                        toOffsetDateTime(session.getUpdatedAt()),
                        session.getActivePatientId(),
                        session.getMessageCount(),
                        session.getLastMessagePreview()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        s.id as "id",
                        s.title as "title",
                        s.created_at as "createdAt",
                        s.updated_at as "updatedAt",
                        s.active_patient_id as "activePatientId",
                        coalesce(message_counts.message_count, 0) as "messageCount",
                        '' as "lastMessagePreview"
                    from chat_sessions s
                    left join lateral (
                        select count(*)::int as message_count
                        from chat_messages m
                        where m.session_id = s.id
                    ) message_counts on true
                    where s.user_id = :userId
                      and s.created_at >= :fromDate and s.created_at < :toDate
                    order by s.created_at asc
                    """,
            nativeQuery = true
    )
    List<ChatSessionSummaryView> findSessionViewsByDateRangeForUser(
            @Param("userId") UUID userId,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate
    );

    default List<ChatSessionSummary> findSessionsByDateRangeForUser(UUID userId, OffsetDateTime fromDate, OffsetDateTime toDate) {
        return findSessionViewsByDateRangeForUser(userId, fromDate, toDate)
                .stream()
                .map(session -> new ChatSessionSummary(
                        session.getId(),
                        session.getTitle(),
                        toOffsetDateTime(session.getCreatedAt()),
                        toOffsetDateTime(session.getUpdatedAt()),
                        session.getActivePatientId(),
                        session.getMessageCount(),
                        session.getLastMessagePreview()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        s.id as "id",
                        s.title as "title",
                        s.created_at as "createdAt",
                        s.updated_at as "updatedAt",
                        s.active_patient_id as "activePatientId",
                        coalesce(message_counts.message_count, 0) as "messageCount",
                        left(coalesce(last_message.content, ''), 160) as "lastMessagePreview"
                    from chat_sessions s
                    left join lateral (
                        select count(*)::int as message_count
                        from chat_messages m
                        where m.session_id = s.id
                    ) message_counts on true
                    left join lateral (
                        select m.content
                        from chat_messages m
                        where m.session_id = s.id
                        order by m.created_at desc
                        limit 1
                    ) last_message on true
                    where s.user_id = :userId
                      and (
                        lower(coalesce(s.title, '')) like concat('%', lower(:query), '%')
                        or lower(coalesce(s.active_patient_id, '')) like concat('%', lower(:query), '%')
                        or exists (
                            select 1
                            from chat_messages search_messages
                            where search_messages.session_id = s.id
                              and lower(search_messages.content) like concat('%', lower(:query), '%')
                        )
                      )
                    order by s.updated_at desc
                    limit :limit
                    """,
            nativeQuery = true
    )
    List<ChatSessionSummaryView> findSearchSessionViewsForUser(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("limit") int limit
    );

    default List<ChatSessionSummary> searchSessionsForUser(UUID userId, String query, int limit) {
        return findSearchSessionViewsForUser(userId, query, limit)
                .stream()
                .map(session -> new ChatSessionSummary(
                        session.getId(),
                        session.getTitle(),
                        toOffsetDateTime(session.getCreatedAt()),
                        toOffsetDateTime(session.getUpdatedAt()),
                        session.getActivePatientId(),
                        session.getMessageCount(),
                        session.getLastMessagePreview()
                ))
                .toList();
    }

    @Query(
            value = """
                    select
                        m.id as "id",
                        m.role as "role",
                        m.content as "content",
                        m.created_at as "createdAt",
                        (
                            select f.rating from message_feedback f
                            where f.message_id = m.id
                            order by f.created_at desc
                            limit 1
                        ) as "feedbackRating",
                        (
                            select f.comment from message_feedback f
                            where f.message_id = m.id
                            order by f.created_at desc
                            limit 1
                        ) as "feedbackComment"
                    from chat_messages m
                    join chat_sessions s on s.id = m.session_id
                    where s.id = :sessionId and s.user_id = :userId
                    order by m.created_at asc
                    """,
            nativeQuery = true
    )
    List<ChatMessageItemView> findMessageItemViewsForSession(
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId
    );

    default List<ChatMessageItem> findMessagesForSession(UUID sessionId, UUID userId) {
        return findMessageItemViewsForSession(sessionId, userId)
                .stream()
                .map(message -> new ChatMessageItem(
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        toOffsetDateTime(message.getCreatedAt()),
                        message.getFeedbackRating() == null
                                ? null
                                : new ChatMessageItem.Feedback(
                                        message.getFeedbackRating(),
                                        message.getFeedbackComment()
                                )
                ))
                .toList();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    interface ChatContextMessageView {
        String getRole();

        String getContent();
    }

    interface ChatSessionSummaryView {
        UUID getId();

        String getTitle();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        String getActivePatientId();

        int getMessageCount();

        String getLastMessagePreview();
    }

    interface ChatMessageItemView {
        UUID getId();

        String getRole();

        String getContent();

        Instant getCreatedAt();

        Integer getFeedbackRating();

        String getFeedbackComment();
    }
}
