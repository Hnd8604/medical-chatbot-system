package com.medicalchatbot.backend.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PdfGenerationTest {

    @Test
    public void testFontLoading() {
        System.out.println("=== PDF GENERATION TEST STARTED ===");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("Java home: " + System.getProperty("java.home"));
        try {
            String regularPath = "C:/Windows/Fonts/arial.ttf";
            String boldPath = "C:/Windows/Fonts/arialbd.ttf";
            
            System.out.println("Regular font exists: " + new File(regularPath).exists());
            System.out.println("Bold font exists: " + new File(boldPath).exists());

            BaseFont regularBase = BaseFont.createFont(regularPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            System.out.println("Regular BaseFont created successfully.");
            
            BaseFont boldBase = BaseFont.createFont(boldPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            System.out.println("Bold BaseFont created successfully.");

            assertNotNull(regularBase);
            assertNotNull(boldBase);

            Font titleFont = new Font(boldBase, 18);
            Font sessionFont = new Font(boldBase, 14);
            Font roleFont = new Font(boldBase, 11);
            Font contentFont = new Font(regularBase, 11);

            Document document = new Document();
            PdfWriter.getInstance(document, new FileOutputStream("test-output.pdf"));
            document.open();
            
            document.add(new Paragraph("Medical Chatbot Export", titleFont));
            document.add(new Paragraph("Session 123", sessionFont));
            document.add(new Paragraph("[2026-06-15] USER: Bệnh nhân demo-patient-001 hiện đang sử dụng hai loại thuốc sau:", roleFont));
            document.add(new Paragraph("1. Amlodipine 5 mg - Liều dùng: Một viên mỗi ngày, uống bụng miếng.", contentFont));
            document.close();
            System.out.println("PDF generated successfully at test-output.pdf");

        } catch (Exception e) {
            System.err.println("TEST FAILED WITH EXCEPTION:");
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
