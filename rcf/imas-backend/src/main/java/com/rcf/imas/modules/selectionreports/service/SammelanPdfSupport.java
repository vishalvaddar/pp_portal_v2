package com.rcf.imas.modules.selectionreports.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * downloadSammelanPDF (selectionReportsController.js:459-522) parity. A4 LANDSCAPE (Firm Decision 8),
 * shared header drawn ONCE (not per report-payload item, unlike the other 4 PDFs), 9-column table,
 * dd/MM/yyyy dates with '--' for null. No disk archive; cohort is interpolated into the filename
 * exactly like Node, but through Spring's ContentDisposition builder for safe header encoding
 * (Firm Decision 6 / ground truth quirk 12).
 */
@Component
public class SammelanPdfSupport {

    public record GeneratedPdf(byte[] bytes, String filename) {}

    private static final String TITLE = "RAJALAKSHMI CHILDREN FOUNDATION";
    private static final String ADDRESS = "Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Near Kolhapur Circle, Belagavi 590016";
    private static final String CONTACT = "Contact No. +91 9444900755, +91 9606930208";
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final byte[] logoLeft;
    private final byte[] logoRight;

    public SammelanPdfSupport() {
        this.logoLeft = readIfPresent("exam-assets/rcf_logo-removebg-preview.png");
        this.logoRight = readIfPresent("exam-assets/logo.png");
    }

    private static byte[] readIfPresent(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            if (!res.exists()) return null;
            return res.getInputStream().readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public GeneratedPdf build(Map<String, Object> body) throws DocumentException {
        String cohort = String.valueOf(body.get("cohort")); // Node: `${cohort}` -> literal "null" if absent, preserved
        List<Map<String, Object>> reportPayload = (List<Map<String, Object>>) body.getOrDefault("reportPayload", List.of());

        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        drawReportHeader(doc, cohort);
        Paragraph title = new Paragraph("Sammelan Attendance Report", new Font(Font.HELVETICA, 16, Font.BOLD));
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(10f);
        doc.add(title);

        for (Map<String, Object> item : reportPayload) {
            Object chartImage = item.get("chartImage");
            if (chartImage != null) {
                String raw = String.valueOf(chartImage).replaceFirst("^data:image/\\w+;base64,", "");
                try {
                    byte[] imgBytes = Base64.getDecoder().decode(raw);
                    Image img = Image.getInstance(imgBytes);
                    img.scaleToFit(700, 250);
                    doc.add(img);
                    doc.add(Chunk.NEWLINE);
                } catch (Exception ignored) {
                    // malformed chart image -- skip, matches Node's lack of validation
                }
            }

            PdfPTable table = new PdfPTable(9);
            table.setWidthPercentage(100);
            for (String h : new String[]{"Event Title", "District", "Block", "Location", "Start Date", "End Date", "Boys", "Girls", "Total"}) {
                PdfPCell cell = new PdfPCell(new Phrase(h, new Font(Font.HELVETICA, 10, Font.BOLD)));
                cell.setPadding(5f);
                table.addCell(cell);
            }

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) item.getOrDefault("blocks", List.of());
            for (Map<String, Object> b : blocks) {
                int boys = toInt(b.get("boys_sel"));
                int girls = toInt(b.get("girls_sel"));
                addCell(table, orEmpty(b.get("label")));
                addCell(table, orEmpty(b.get("district_name")));
                addCell(table, orEmpty(b.get("block_name")));
                addCell(table, orEmpty(b.get("event_location")));
                addCell(table, formatDate(b.get("from_date")));
                addCell(table, formatDate(b.get("to_date")));
                addCell(table, String.valueOf(boys));
                addCell(table, String.valueOf(girls));
                addCell(table, String.valueOf(boys + girls));
            }
            doc.add(table);
        }
        doc.close();

        String filename = "Sammelan_Report_" + cohort + ".pdf";
        return new GeneratedPdf(out.toByteArray(), filename);
    }

    private void drawReportHeader(Document doc, String cohort) throws DocumentException {
        PdfPTable headerRow = new PdfPTable(new float[]{1f, 4f, 1f});
        headerRow.setWidthPercentage(100);
        headerRow.addCell(logoCell(logoLeft));

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        Paragraph title = new Paragraph(TITLE, new Font(Font.TIMES_ROMAN, 18, Font.BOLD));
        title.setAlignment(Element.ALIGN_CENTER);
        Paragraph subtitle = new Paragraph("PRATIBHA POSHAK - " + cohort, new Font(Font.TIMES_ROMAN, 16, Font.NORMAL));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(title);
        titleCell.addElement(subtitle);
        headerRow.addCell(titleCell);
        headerRow.addCell(logoCell(logoRight));
        doc.add(headerRow);

        Font addressFont = new Font(Font.TIMES_ROMAN, 8, Font.NORMAL);
        Paragraph address = new Paragraph(ADDRESS, addressFont);
        address.setAlignment(Element.ALIGN_CENTER);
        Paragraph contact = new Paragraph(CONTACT, addressFont);
        contact.setAlignment(Element.ALIGN_CENTER);
        contact.setSpacingAfter(8f);
        doc.add(address);
        doc.add(contact);
        doc.add(new Chunk(new LineSeparator()));
        doc.add(Chunk.NEWLINE);
    }

    private PdfPCell logoCell(byte[] bytes) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        if (bytes != null) {
            try {
                Image img = Image.getInstance(bytes);
                img.scaleToFit(50, 50);
                cell.addElement(img);
            } catch (Exception ignored) {
                // logo genuinely missing/corrupt -- omit silently
            }
        }
        return cell;
    }

    private static void addCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 9, Font.NORMAL)));
        cell.setPadding(4f);
        table.addCell(cell);
    }

    private static String orEmpty(Object v) { return v == null ? "" : String.valueOf(v); }

    private static int toInt(Object v) {
        if (v == null) return 0;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** formatDate parity (selectionReportsController.js:474): dd/MM/yyyy, '--' for null/blank/unparseable. */
    static String formatDate(Object v) {
        if (v == null) return "--";
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "--";
        try {
            LocalDate d = LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            return DDMMYYYY.format(d);
        } catch (Exception e) {
            return "--";
        }
    }
}
