package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.enums.ExportFormat;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.service.TranscriptRenderer.Line;
import com.medicalchatbot.backend.service.TranscriptRenderer.Transcript;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Downloads of the user's own chat history. Every export is audited because it contains PHI. */
@Service
@RequiredArgsConstructor
public class ChatExportService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionService chatSessionService;
    private final CurrentUserService currentUserService;
    private final TranscriptRenderer transcriptRenderer;
    private final AuditService auditService;
    private final AppProperties appProperties;

    @Transactional
    public ExportFile exportSession(UUID sessionId, ExportFormat format) {
        User user = currentUserService.requireCurrentUser();
        ChatSession session = chatSessionService.requireOwnedSession(sessionId, user.getId());
        Transcript transcript = transcript(user, session.getId(), session.getTitle());

        audit(user, AuditResource.EXPORT_SESSION, sessionId.toString(), format);
        return new ExportFile("session_" + sessionId + "." + format.extension(), format,
                transcriptRenderer.render(List.of(transcript), format));
    }

    /** Sessions created on the calendar days {@code [from, to]} of the application time zone. */
    @Transactional
    public ExportFile exportSessionsCreatedBetween(LocalDate from, LocalDate to, ExportFormat format) {
        if (from.isAfter(to)) {
            throw AppException.invalid("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
        }
        User user = currentUserService.requireCurrentUser();
        ZoneId zone = appProperties.timezone();
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<Transcript> transcripts = chatSessionRepository.findSessionsCreatedBetween(user.getId(), start, end).stream()
                .map(session -> transcript(user, session.id(), session.title()))
                .toList();

        audit(user, AuditResource.EXPORT_DATE_RANGE, from + " to " + to, format);
        return new ExportFile("chat_history_" + from + "_to_" + to + "." + format.extension(), format,
                transcriptRenderer.render(transcripts, format));
    }

    private Transcript transcript(User user, UUID sessionId, String title) {
        List<Line> lines = chatSessionRepository.findMessages(sessionId, user.getId()).stream()
                .map(this::toLine)
                .toList();
        return new Transcript(title != null && !title.isBlank() ? title : "Session " + sessionId, lines);
    }

    private Line toLine(ChatMessageProjection message) {
        String time = message.createdAt() == null
                ? ""
                : TIME_FORMAT.format(message.createdAt().atZoneSameInstant(appProperties.timezone()));
        return new Line(time, message.role(), message.content());
    }

    private void audit(User user, AuditResource resource, String resourceId, ExportFormat format) {
        auditService.record(user, AuditAction.EXPORT_CONVERSATION, resource, resourceId,
                auditService.metadata().put("format", format.extension()));
    }

    public record ExportFile(String filename, ExportFormat format, byte[] content) {
    }
}
