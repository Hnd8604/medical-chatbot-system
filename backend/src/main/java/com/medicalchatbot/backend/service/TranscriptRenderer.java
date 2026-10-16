package com.medicalchatbot.backend.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import com.medicalchatbot.backend.enums.ExportFormat;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders chat transcripts as PDF or CSV. The PDF embeds the bundled Roboto fonts so Vietnamese
 * text renders the same on every operating system.
 */
@Component
public class TranscriptRenderer {

    private static final String REGULAR_FONT = "fonts/Roboto-Regular.ttf";
    private static final String BOLD_FONT = "fonts/Roboto-Bold.ttf";
    private static final String[] CSV_HEADER = {"Session", "Time", "Role", "Content"};
    /** Lets Excel detect UTF-8. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final Font titleFont;
    private final Font sessionFont;
    private final Font roleFont;
    private final Font contentFont;

    public TranscriptRenderer() {
        BaseFont regular = loadFont(REGULAR_FONT);
        BaseFont bold = loadFont(BOLD_FONT);
        this.titleFont = new Font(bold, 18);
        this.sessionFont = new Font(bold, 14);
        this.roleFont = new Font(bold, 11);
        this.contentFont = new Font(regular, 11);
    }

    public byte[] render(List<Transcript> transcripts, ExportFormat format) {
        return switch (format) {
            case PDF -> renderPdf(transcripts);
            case CSV -> renderCsv(transcripts);
        };
    }

    private byte[] renderPdf(List<Transcript> transcripts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, output);
        document.open();
        document.add(new Paragraph("Medical Chatbot Export", titleFont));
        document.add(new Paragraph(" "));
        for (Transcript transcript : transcripts) {
            document.add(new Paragraph(transcript.title(), sessionFont));
            document.add(new Paragraph(" "));
            for (Line line : transcript.lines()) {
                document.add(new Paragraph("[" + line.time() + "] " + line.role().toUpperCase(Locale.ROOT) + ":", roleFont));
                document.add(new Paragraph(line.content(), contentFont));
                document.add(new Paragraph(" "));
            }
            document.add(new Paragraph("--------------------------------------------------"));
            document.add(new Paragraph(" "));
        }
        document.close();
        return output.toByteArray();
    }

    private byte[] renderCsv(List<Transcript> transcripts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(UTF8_BOM);
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(CSV_HEADER).build();
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8), format)) {
            for (Transcript transcript : transcripts) {
                for (Line line : transcript.lines()) {
                    printer.printRecord(transcript.title(), line.time(), line.role(), line.content());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write CSV export", exception);
        }
        return output.toByteArray();
    }

    private static BaseFont loadFont(String resource) {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            String fileName = resource.substring(resource.lastIndexOf('/') + 1);
            return BaseFont.createFont(fileName, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, input.readAllBytes(), null);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load export font " + resource, exception);
        }
    }

    /** One conversation; {@code time} is already formatted in the application time zone. */
    public record Transcript(String title, List<Line> lines) {
    }

    public record Line(String time, String role, String content) {
    }
}
