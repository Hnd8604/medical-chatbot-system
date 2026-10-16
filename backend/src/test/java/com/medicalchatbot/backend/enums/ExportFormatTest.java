package com.medicalchatbot.backend.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medicalchatbot.backend.exception.AppException;
import org.junit.jupiter.api.Test;

class ExportFormatTest {

    @Test
    void formatParameterIsCaseInsensitive() {
        assertEquals(ExportFormat.CSV, ExportFormat.fromParameter(" CSV "));
        assertEquals(ExportFormat.PDF, ExportFormat.fromParameter("pdf"));
    }

    @Test
    void unknownFormatParameterIsRejected() {
        assertThrows(AppException.class, () -> ExportFormat.fromParameter("docx"));
        assertThrows(AppException.class, () -> ExportFormat.fromParameter(null));
    }
}
