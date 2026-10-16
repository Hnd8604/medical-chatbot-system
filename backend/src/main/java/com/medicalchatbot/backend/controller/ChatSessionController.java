package com.medicalchatbot.backend.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.chat.RenameSessionRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatMessageResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatSessionResponse;
import com.medicalchatbot.backend.enums.ExportFormat;
import com.medicalchatbot.backend.service.ChatExportService.ExportFile;
import com.medicalchatbot.backend.service.ChatExportService;
import com.medicalchatbot.backend.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Chat history", description = "The signed-in user's conversations: listing, reading, renaming, deleting and exporting")
public class ChatSessionController {

    ChatSessionService chatSessionService;
    ChatExportService chatExportService;

    @GetMapping
    @Operation(summary = "List my conversations",
            description = "Most recently active first; query searches titles and messages.")
    public ApiResponse<List<ChatSessionResponse>> list(
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.<List<ChatSessionResponse>>builder()
                .result(chatSessionService.list(query, limit))
                .message(ResponseMessage.GET_CHAT_SESSIONS_SUCCESS)
                .build();
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(summary = "Read a conversation", description = "All messages of one of my conversations, oldest first.")
    public ApiResponse<List<ChatMessageResponse>> messages(@PathVariable UUID sessionId) {
        return ApiResponse.<List<ChatMessageResponse>>builder()
                .result(chatSessionService.messages(sessionId))
                .message(ResponseMessage.GET_CHAT_MESSAGES_SUCCESS)
                .build();
    }

    @PatchMapping("/{sessionId}")
    @Operation(summary = "Rename a conversation")
    public ApiResponse<ChatSessionRenameResponse> rename(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameSessionRequest request
    ) {
        return ApiResponse.<ChatSessionRenameResponse>builder()
                .result(chatSessionService.rename(sessionId, request.title()))
                .message(ResponseMessage.RENAME_CHAT_SESSION_SUCCESS)
                .build();
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Delete a conversation",
            description = "Deletes its messages and ratings; usage and audit records are kept.")
    public ApiResponse<Void> delete(@PathVariable UUID sessionId) {
        chatSessionService.delete(sessionId);
        return ApiResponse.<Void>builder().message(ResponseMessage.DELETE_CHAT_SESSION_SUCCESS).build();
    }

    @GetMapping("/{sessionId}/export")
    @Operation(summary = "Export a conversation", description = "Downloads one conversation as a PDF or CSV file.")
    public ResponseEntity<byte[]> exportSession(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        return download(chatExportService.exportSession(sessionId, ExportFormat.fromParameter(format)));
    }

    @GetMapping("/export")
    @Operation(summary = "Export conversations by date",
            description = "Downloads every conversation started between two dates (inclusive) as one PDF or CSV file.")
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
