package com.medicalchatbot.backend.enums;

import java.util.Locale;

import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;

/** File formats supported when exporting chat history. */
public enum ExportFormat {
    PDF("application/pdf", "pdf"),
    CSV("text/csv; charset=UTF-8", "csv");

    private final String contentType;
    private final String extension;

    ExportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** Parses the {@code format} query parameter case-insensitively ({@code pdf}, {@code csv}). */
    public static ExportFormat fromParameter(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Định dạng xuất chỉ hỗ trợ pdf hoặc csv.");
        }
    }
}
