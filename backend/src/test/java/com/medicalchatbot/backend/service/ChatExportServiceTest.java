package com.medicalchatbot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.ExportFormat;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.mapper.ChatMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.projection.ChatMessageProjection;
import com.medicalchatbot.backend.service.ChatExportService.ExportFile;
import com.medicalchatbot.backend.support.TestProperties;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatExportServiceTest {

    /** Vietnamese diacritics must survive the export, which the bundled fonts guarantee. */
    private static final String VIETNAMESE_ANSWER = "Bệnh nhân đang dùng Amlodipine 5 mg, uống một viên mỗi ngày.";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private ChatExportService service;
    private User user;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        service = new ChatExportService(
                chatSessionRepository,
                userRepository,
                new ChatSessionService(chatSessionRepository, Mappers.getMapper(ChatMapper.class)),
                new TranscriptRenderer(),
                new AuditService(auditLogRepository, Mappers.getMapper(AuditLogMapper.class), new ObjectMapper()),
                TestProperties.app()
        );
        user = TestUsers.user(UserRole.USER);
        session = ChatSession.builder().id(UUID.randomUUID()).user(user).title("Thuốc đang dùng").build();
        TestUsers.signIn(user, userRepository);
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void sessionExportsAsPdfWithEmbeddedFonts() {
        givenSessionWithMessages();

        ExportFile file = service.exportSession(session.getId(), ExportFormat.PDF);

        assertEquals("session_" + session.getId() + ".pdf", file.filename());
        assertThat(new String(file.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(new String(file.content(), StandardCharsets.ISO_8859_1)).contains("Roboto");
        assertEquals("EXPORT_CONVERSATION", savedAudit().getAction());
    }

    @Test
    void sessionExportsAsCsvInTheApplicationTimeZone() {
        givenSessionWithMessages();

        ExportFile file = service.exportSession(session.getId(), ExportFormat.CSV);

        String csv = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Session,Time,Role,Content");
        // 2026-09-25T08:00Z is 15:00 in Asia/Ho_Chi_Minh; the comma in the answer forces quoting.
        assertThat(csv).contains("Thuốc đang dùng,2026-09-25 15:00:00,assistant,\"" + VIETNAMESE_ANSWER + "\"");
        assertEquals("csv", savedAudit().getMetadataJson().path("format").asText());
    }

    @Test
    void foreignSessionCannotBeExported() {
        when(chatSessionRepository.findByIdAndUserId(session.getId(), user.getId())).thenReturn(Optional.empty());

        assertThrows(AppException.class, () -> service.exportSession(session.getId(), ExportFormat.PDF));
    }

    @Test
    void dateRangeExportCoversWholeLocalDays() {
        LocalDate day = LocalDate.parse("2026-09-25");
        when(chatSessionRepository.findSessionsCreatedBetween(any(), any(), any())).thenReturn(List.of());

        ExportFile file = service.exportSessionsCreatedBetween(day, day, ExportFormat.CSV);

        assertEquals("chat_history_2026-09-25_to_2026-09-25.csv", file.filename());
        verify(chatSessionRepository).findSessionsCreatedBetween(user.getId(),
                OffsetDateTime.parse("2026-09-25T00:00:00+07:00"), OffsetDateTime.parse("2026-09-26T00:00:00+07:00"));
    }

    private void givenSessionWithMessages() {
        when(chatSessionRepository.findByIdAndUserId(session.getId(), user.getId())).thenReturn(Optional.of(session));
        when(chatSessionRepository.findMessages(session.getId(), user.getId())).thenReturn(List.of(
                new ChatMessageProjection(UUID.randomUUID(), "user", "Tôi đang dùng thuốc gì?",
                        OffsetDateTime.parse("2026-09-25T07:59:00Z"), null, null, null, null, null, null, null, null),
                new ChatMessageProjection(UUID.randomUUID(), "assistant", VIETNAMESE_ANSWER,
                        OffsetDateTime.parse("2026-09-25T08:00:00Z"), null, null, null, null, null, null, null, null)
        ));
    }

    private AuditLog savedAudit() {
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        return audit.getValue();
    }
}
