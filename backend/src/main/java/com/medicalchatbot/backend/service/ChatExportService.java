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
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.service.TranscriptRenderer.Line;
import com.medicalchatbot.backend.service.TranscriptRenderer.Transcript;
import com.medicalchatbot.backend.util.SecurityUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Downloads of the user's own chat history. Every export is audited because it contains PHI. */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatExportService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    ChatSessionRepository chatSessionRepository;
    UserRepository userRepository;
    ChatSessionService chatSessionService;
    TranscriptRenderer transcriptRenderer;
    AuditService auditService;
    AppProperties appProperties;

    @Transactional
    public ExportFile exportSession(UUID sessionId, ExportFormat format) {
        User user = userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
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
            throw new AppException(ErrorCode.DATE_RANGE_INVALID);
        }
        User user = userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
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
