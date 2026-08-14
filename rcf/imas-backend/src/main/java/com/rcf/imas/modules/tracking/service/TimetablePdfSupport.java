package com.rcf.imas.modules.tracking.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * OpenPDF port of activeTimeTableController.js's downloadTimetablePDF (Firm Decision 3). Renders ONLY the
 * client-posted payload -- no DB re-query. Text-only header (no fs.existsSync-gated logo images -- dropped,
 * matching CustomListPdfSupport's precedent for a human-facing download with no automated consumer).
 * Functional table layout (DAY/TIME/SUBJECT/TEACHER/BATCH, all uppercased), not pixel-perfect vs. Node's
 * pdfkit-table original. Hard-coded "PRATIBHA POSHAK EXAMINATION - 2025" header text reproduced verbatim
 * (ground truth §7 quirk 8 -- faithful parity; flagged in the plan's Deferred section as worth fixing).
 */
@Component
public class TimetablePdfSupport {

    private static final Font TITLE_FONT = new Font(Font.TIMES_ROMAN, 16, Font.BOLD);
    private static final Font SUBTITLE_FONT = new Font(Font.TIMES_ROMAN, 12, Font.NORMAL);
    private static final Font ADDRESS_FONT = new Font(Font.TIMES_ROMAN, 9, Font.NORMAL);
    private static final Font HEADER_CELL_FONT = new Font(Font.TIMES_ROMAN, 10, Font.BOLD);
    private static final Font BODY_CELL_FONT = new Font(Font.TIMES_ROMAN, 9, Font.NORMAL);

    @SuppressWarnings("unchecked")
    public byte[] build(Map<String, Object> payload) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.getOrDefault("timetableData", List.of());
        String cohortName = String.valueOf(payload.getOrDefault("cohortName", ""));
        String viewType = String.valueOf(payload.getOrDefault("viewType", ""));
        Map<String, Object> filterDetails = (Map<String, Object>) payload.getOrDefault("filterDetails", Map.of());

        String subtitle = switch (viewType) {
            case "teacher" -> "TEACHER: " + filterDetails.getOrDefault("teacherName", "");
            case "batch" -> "COHORT: " + cohortName + " | BATCH: " + filterDetails.getOrDefault("batchName", "ALL BATCHES");
            default -> "COHORT: " + cohortName;
        };

        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph title = new Paragraph("RAJALAKSHMI CHILDREN FOUNDATION", TITLE_FONT);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);

            Paragraph examLine = new Paragraph("PRATIBHA POSHAK EXAMINATION - 2025", SUBTITLE_FONT); // hard-coded, matches Node
            examLine.setAlignment(Element.ALIGN_CENTER);
            doc.add(examLine);

            Paragraph subtitlePara = new Paragraph(subtitle, SUBTITLE_FONT);
            subtitlePara.setAlignment(Element.ALIGN_CENTER);
            subtitlePara.setSpacingAfter(10f);
            doc.add(subtitlePara);

            float[] widths = {80f, 140f, 200f, 150f, 110f};
            PdfPTable table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            for (String h : new String[]{"DAY", "TIME", "SUBJECT", "TEACHER", "BATCH"}) {
                PdfPCell cell = new PdfPCell(new Phrase(h, HEADER_CELL_FONT));
                cell.setGrayFill(0.85f);
                cell.setPadding(12f);
                table.addCell(cell);
            }
            for (Map<String, Object> row : rows) {
                String day = upper(row.get("day_of_week"));
                String time = upper(row.get("start_time")) + " - " + upper(row.get("end_time")); // Node: `${start} - ${end}`
                String subject = upper(row.get("subject_name"));
                String teacher = upper(row.get("teacher_name"));
                String batch = upper(row.get("batch_name"));
                for (String cellText : new String[]{day, time, subject, teacher, batch}) {
                    PdfPCell cell = new PdfPCell(new Phrase(cellText, BODY_CELL_FONT));
                    cell.setPadding(12f);
                    cell.setMinimumHeight(35f);
                    table.addCell(cell);
                }
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }
    }

    private static String upper(Object o) {
        return o == null ? "" : String.valueOf(o).toUpperCase();
    }
}
