package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ExportController {

    private final ExportService exportService;

    @GetMapping("/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportSession(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        byte[] data;
        String contentType;
        String extension;

        if ("csv".equalsIgnoreCase(format)) {
            data = exportService.exportSessionAsCsv(sessionId);
            contentType = "text/csv; charset=UTF-8";
            extension = ".csv";
        } else {
            data = exportService.exportSessionAsPdf(sessionId);
            contentType = MediaType.APPLICATION_PDF_VALUE;
            extension = ".pdf";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"session_" + sessionId + extension + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(data);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportSessionsByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        byte[] data;
        String contentType;
        String extension;

        if ("csv".equalsIgnoreCase(format)) {
            data = exportService.exportSessionsByDateAsCsv(from, to);
            contentType = "text/csv; charset=UTF-8";
            extension = ".csv";
        } else {
            data = exportService.exportSessionsByDateAsPdf(from, to);
            contentType = MediaType.APPLICATION_PDF_VALUE;
            extension = ".pdf";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"chat_history_" + from + "_to_" + to + extension + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(data);
    }
}
