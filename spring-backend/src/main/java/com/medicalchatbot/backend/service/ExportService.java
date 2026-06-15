package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ExportService(
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            AuditLogRepository auditLogRepository,
            CurrentUserService currentUserService,
            UserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.auditLogRepository = auditLogRepository;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    private User getCurrentUser() {
        String username = currentUserService.getCurrentUsername();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found: " + username));
    }

    public byte[] exportSessionAsPdf(UUID sessionId) {
        User user = getCurrentUser();
        if (!chatSessionRepository.existsForUser(sessionId, user.getId())) {
            throw new IllegalArgumentException("Session not found or permission denied");
        }

        List<ChatMessageItem> messages = chatSessionRepository.findMessagesForSession(sessionId, user.getId());

        byte[] pdfBytes = generatePdf(List.of(new SessionExportData("Session " + sessionId, messages)));
        logExportAction(user, "SESSION", sessionId.toString(), "pdf");
        return pdfBytes;
    }

    public byte[] exportSessionAsCsv(UUID sessionId) {
        User user = getCurrentUser();
        if (!chatSessionRepository.existsForUser(sessionId, user.getId())) {
            throw new IllegalArgumentException("Session not found or permission denied");
        }

        List<ChatMessageItem> messages = chatSessionRepository.findMessagesForSession(sessionId, user.getId());

        byte[] csvBytes = generateCsv(List.of(new SessionExportData("Session " + sessionId, messages)));
        logExportAction(user, "SESSION", sessionId.toString(), "csv");
        return csvBytes;
    }

    public byte[] exportSessionsByDateAsPdf(LocalDate fromDate, LocalDate toDate) {
        User user = getCurrentUser();
        OffsetDateTime from = fromDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<ChatSessionSummary> sessions = chatSessionRepository.findSessionsByDateRangeForUser(user.getId(), from, to);
        List<SessionExportData> exportDataList = sessions.stream().map(session -> {
            List<ChatMessageItem> messages = chatSessionRepository.findMessagesForSession(session.id(), user.getId());
            return new SessionExportData(session.title() != null ? session.title() : "Session " + session.id(), messages);
        }).toList();

        byte[] pdfBytes = generatePdf(exportDataList);
        logExportAction(user, "DATE_RANGE", fromDate + " to " + toDate, "pdf");
        return pdfBytes;
    }

    public byte[] exportSessionsByDateAsCsv(LocalDate fromDate, LocalDate toDate) {
        User user = getCurrentUser();
        OffsetDateTime from = fromDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<ChatSessionSummary> sessions = chatSessionRepository.findSessionsByDateRangeForUser(user.getId(), from, to);
        List<SessionExportData> exportDataList = sessions.stream().map(session -> {
            List<ChatMessageItem> messages = chatSessionRepository.findMessagesForSession(session.id(), user.getId());
            return new SessionExportData(session.title() != null ? session.title() : "Session " + session.id(), messages);
        }).toList();

        byte[] csvBytes = generateCsv(exportDataList);
        logExportAction(user, "DATE_RANGE", fromDate + " to " + toDate, "csv");
        return csvBytes;
    }

    private byte[] generatePdf(List<SessionExportData> sessionsData) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            // Load Arial fonts for Vietnamese Unicode support (Windows specific)
            Font titleFont;
            Font sessionFont;
            Font roleFont;
            Font contentFont;
            try {
                BaseFont regularBase = BaseFont.createFont("C:/Windows/Fonts/arial.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                BaseFont boldBase = BaseFont.createFont("C:/Windows/Fonts/arialbd.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                titleFont = new Font(boldBase, 18);
                sessionFont = new Font(boldBase, 14);
                roleFont = new Font(boldBase, 11);
                contentFont = new Font(regularBase, 11);
            } catch (Exception e) {
                log.error("Failed to load Arial fonts, falling back to Helvetica", e);
                // Fallback if font is missing
                titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
                sessionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
                roleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
                contentFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
            }

            document.add(new Paragraph("Medical Chatbot Export", titleFont));
            document.add(new Paragraph(" "));

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (SessionExportData sessionData : sessionsData) {
                document.add(new Paragraph(sessionData.title(), sessionFont));
                document.add(new Paragraph(" "));

                for (ChatMessageItem msg : sessionData.messages()) {
                    String time = msg.createdAt() != null ? formatter.format(msg.createdAt()) : "";
                    document.add(new Paragraph(new Phrase("[" + time + "] " + msg.role().toUpperCase() + ": ", roleFont)));
                    document.add(new Paragraph(new Phrase(msg.content(), contentFont)));
                    document.add(new Paragraph(" "));
                }
                document.add(new Paragraph("--------------------------------------------------"));
                document.add(new Paragraph(" "));
            }

            document.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new RuntimeException("Failed to generate PDF", e);
        }
    }

    private byte[] generateCsv(List<SessionExportData> sessionsData) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(osw, CSVFormat.DEFAULT.builder().setHeader("Session", "Time", "Role", "Content").build())) {

            // Add BOM for Excel UTF-8 support
            baos.write(0xEF);
            baos.write(0xBB);
            baos.write(0xBF);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (SessionExportData sessionData : sessionsData) {
                for (ChatMessageItem msg : sessionData.messages()) {
                    String time = msg.createdAt() != null ? formatter.format(msg.createdAt()) : "";
                    csvPrinter.printRecord(sessionData.title(), time, msg.role(), msg.content());
                }
            }

            csvPrinter.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate CSV", e);
        }
    }

    private void logExportAction(User user, String resourceType, String resourceId, String format) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("format", format);
        auditLogRepository.save(user, null, "EXPORT_CONVERSATION", resourceType, resourceId, metadata);
    }

    private record SessionExportData(String title, List<ChatMessageItem> messages) {}
}
