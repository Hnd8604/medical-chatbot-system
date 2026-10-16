package com.medicalchatbot.backend.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.repository.projection.ChatContextMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.repository.projection.ChatSessionSummaryProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Chat sessions plus the read models of the chat screen. Every query is scoped to the owning user,
 * so a session id from another account behaves exactly like a missing one.
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    long deleteByIdAndUserId(UUID id, UUID userId);

    /** The last {@code limit} messages of a session in chronological order — the context of the next turn. */
    default List<ChatContextMessageProjection> findRecentMessages(UUID sessionId, UUID userId, int limit) {
        return findRecentMessageRows(sessionId, userId, limit).stream()
                .map(row -> new ChatContextMessageProjection(row.getRole(), row.getContent()))
                .toList();
    }

    /** Most recently active sessions; a non-null {@code query} matches title, patient id or message text. */
    default List<ChatSessionSummaryProjection> searchSessions(UUID userId, String query, int limit) {
        return searchSessionRows(userId, query, limit).stream()
                .map(ChatSessionRepository::toSummary)
                .toList();
    }

    /** Sessions created in {@code [from, to)}, oldest first — used to export a date range. */
    default List<ChatSessionSummaryProjection> findSessionsCreatedBetween(
            UUID userId,
            OffsetDateTime from,
            OffsetDateTime to
    ) {
        return findSessionRowsCreatedBetween(userId, from, to).stream()
                .map(ChatSessionRepository::toSummary)
                .toList();
    }

    default List<ChatMessageProjection> findMessages(UUID sessionId, UUID userId) {
        return findMessageRows(sessionId, userId).stream()
                .map(row -> new ChatMessageProjection(
                        row.getId(),
                        row.getRole(),
                        row.getContent(),
                        toOffsetDateTime(row.getCreatedAt()),
                        row.getFeedbackRating(),
                        row.getFeedbackComment(),
                        row.getResponseStatus(),
                        row.getTraceId(),
                        row.getAnswerSource(),
                        toBoolean(row.getGroundingChecked()),
                        toBoolean(row.getGroundingPassed()),
                        row.getLlmUnavailableReason()
                ))
                .toList();
    }

    @Query(
            value = """
                    select recent.role as "role", recent.content as "content"
                    from (
                        select m.role, m.content, m.created_at
                        from chat_messages m
                        join chat_sessions s on s.id = m.session_id
                        where s.id = :sessionId and s.user_id = :userId
                        order by m.created_at desc
                        limit :limit
                    ) recent
                    order by recent.created_at asc
                    """,
            nativeQuery = true
    )
    List<ContextMessageRow> findRecentMessageRows(
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );

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
                        cast(:query as text) is null
                        or lower(coalesce(s.title, '')) like concat('%', lower(cast(:query as text)), '%')
                        or lower(coalesce(s.active_patient_id, '')) like concat('%', lower(cast(:query as text)), '%')
                        or exists (
                            select 1
                            from chat_messages search_messages
                            where search_messages.session_id = s.id
                              and lower(search_messages.content) like concat('%', lower(cast(:query as text)), '%')
                        )
                      )
                    order by s.updated_at desc
                    limit :limit
                    """,
            nativeQuery = true
    )
    List<SessionSummaryRow> searchSessionRows(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("limit") int limit
    );

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
    List<SessionSummaryRow> findSessionRowsCreatedBetween(
            @Param("userId") UUID userId,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate
    );

    /** Messages with the latest feedback and the answer outcome stored in the assistant metadata. */
    @Query(
            value = """
                    select
                        m.id as "id",
                        m.role as "role",
                        m.content as "content",
                        m.created_at as "createdAt",
                        latest_feedback.rating as "feedbackRating",
                        latest_feedback.comment as "feedbackComment",
                        m.metadata_json ->> 'response_status' as "responseStatus",
                        m.metadata_json ->> 'trace_id' as "traceId",
                        m.metadata_json ->> 'answer_source' as "answerSource",
                        m.metadata_json -> 'grounding' ->> 'checked' as "groundingChecked",
                        m.metadata_json -> 'grounding' ->> 'passed' as "groundingPassed",
                        m.metadata_json ->> 'llm_unavailable_reason' as "llmUnavailableReason"
                    from chat_messages m
                    join chat_sessions s on s.id = m.session_id
                    left join lateral (
                        select f.rating, f.comment
                        from message_feedback f
                        where f.message_id = m.id
                        order by f.created_at desc
                        limit 1
                    ) latest_feedback on true
                    where s.id = :sessionId and s.user_id = :userId
                    order by m.created_at asc
                    """,
            nativeQuery = true
    )
    List<MessageRow> findMessageRows(
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId
    );

    private static ChatSessionSummaryProjection toSummary(SessionSummaryRow row) {
        return new ChatSessionSummaryProjection(
                row.getId(),
                row.getTitle(),
                toOffsetDateTime(row.getCreatedAt()),
                toOffsetDateTime(row.getUpdatedAt()),
                row.getActivePatientId(),
                row.getMessageCount(),
                row.getLastMessagePreview()
        );
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /** jsonb booleans are read as text; a missing key or JSON null stays {@code null}. */
    private static Boolean toBoolean(String value) {
        return value == null ? null : Boolean.valueOf(value);
    }

    interface ContextMessageRow {
        String getRole();

        String getContent();
    }

    interface SessionSummaryRow {
        UUID getId();

        String getTitle();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        String getActivePatientId();

        int getMessageCount();

        String getLastMessagePreview();
    }

    interface MessageRow {
        UUID getId();

        String getRole();

        String getContent();

        Instant getCreatedAt();

        Integer getFeedbackRating();

        String getFeedbackComment();

        String getResponseStatus();

        String getTraceId();

        String getAnswerSource();

        String getGroundingChecked();

        String getGroundingPassed();

        String getLlmUnavailableReason();
    }
}
