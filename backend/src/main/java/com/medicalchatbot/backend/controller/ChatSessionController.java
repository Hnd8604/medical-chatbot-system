package com.medicalchatbot.backend.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.RenameSessionRequest;
import com.medicalchatbot.backend.dto.response.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionResponse;
import com.medicalchatbot.backend.enums.ExportFormat;
import com.medicalchatbot.backend.service.ChatExportService;
import com.medicalchatbot.backend.service.ChatExportService.ExportFile;
import com.medicalchatbot.backend.service.ChatSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's conversation history. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatExportService chatExportService;

    @GetMapping
    public List<ChatSessionResponse> list(
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return chatSessionService.list(query, limit);
    }

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageResponse> messages(@PathVariable UUID sessionId) {
        return chatSessionService.messages(sessionId);
    }

    @PatchMapping("/{sessionId}")
    public ChatSessionRenameResponse rename(@PathVariable UUID sessionId, @Valid @RequestBody RenameSessionRequest request) {
        return chatSessionService.rename(sessionId, request.title());
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID sessionId) {
        chatSessionService.delete(sessionId);
    }

    @GetMapping("/{sessionId}/export")
    public ResponseEntity<byte[]> exportSession(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        return download(chatExportService.exportSession(sessionId, ExportFormat.fromParameter(format)));
    }

    /** All sessions created between two dates (inclusive). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportSessions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        return download(chatExportService.exportSessionsCreatedBetween(from, to, ExportFormat.fromParameter(format)));
    }

    private static ResponseEntity<byte[]> download(ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CONTENT_TYPE, file.format().contentType())
                .body(file.content());
    }
}
