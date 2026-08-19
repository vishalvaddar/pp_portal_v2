package com.rcf.imas.modules.exams.service;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** downloadAllHallTickets() parity: one HallTicketPdfSupport.build(...) call per student, zipped in-memory
 *  via java.util.zip.ZipOutputStream (JDK built-in, no new dependency, Firm Decision 9 -- no disk writes,
 *  unlike Node's per-student temp PDF files + archiver). */
@Component
public class HallTicketZipSupport {

    private final HallTicketPdfSupport pdfSupport;

    public HallTicketZipSupport(HallTicketPdfSupport pdfSupport) { this.pdfSupport = pdfSupport; }

    public byte[] build(List<Map<String, Object>> students) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map<String, Object> student : students) {
                String safeName = sanitize(String.valueOf(student.get("student_name")));
                String safeTicket = sanitize(String.valueOf(student.get("pp_hall_ticket_no")));
                byte[] pdfBytes = pdfSupport.build(student);
                zip.putNextEntry(new ZipEntry(safeName + "_" + safeTicket + ".pdf"));
                zip.write(pdfBytes);
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** sanitizeFilename(name) parity: `[<>:"/\\|?*]` and whitespace -> '_', truncate to 100 chars, null -> "unknown".
     *  Whitespace-to-underscore is required so the resulting Content-Disposition filename token needs no quoting. */
    public static String sanitize(String name) {
        if (name == null || "null".equals(name)) return "unknown";
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*]", "_").replaceAll("\\s+", "_");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
