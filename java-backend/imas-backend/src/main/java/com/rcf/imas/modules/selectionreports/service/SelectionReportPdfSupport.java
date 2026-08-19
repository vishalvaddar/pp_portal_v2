package com.rcf.imas.modules.selectionreports.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OpenPDF port of selectionReportsController.js's drawReportHeader + downloadNMMSPDF /
 * downloadTurnOutPDF / downloadSelectionPDF / downloadSelectsPDF (Firm Decisions 3, 5, 6, 8, 9).
 * Portrait A4, header redrawn per report-payload item (Firm Decision 8). Renders ONLY the
 * client-posted reportPayload -- no DB re-query, no disk archive. Flow-based layout (Paragraphs /
 * PdfPTable), not a pixel-for-pixel clone of pdfkit's absolute x/y model -- same precedent as
 * TimetablePdfSupport / HallTicketPdfSupport elsewhere in this codebase.
 */
@Component
public class SelectionReportPdfSupport {

    public record GeneratedPdf(byte[] bytes, String filename) {}

    private static final String TITLE = "RAJALAKSHMI CHILDREN FOUNDATION";
    private static final String ADDRESS = "Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Near Kolhapur Circle, Belagavi 590016";
    private static final String CONTACT = "Contact No. +91 9444900755, +91 9606930208";
    private static final Color TITLE_COLOR = new Color(0x2c, 0x3e, 0x50);
    private static final Color SUBTITLE_COLOR = new Color(0x64, 0x74, 0x8b);
    private static final Color HEADER_CELL_COLOR = new Color(0x47, 0x55, 0x69);
    private static final Color BODY_CELL_COLOR = new Color(0x1e, 0x29, 0x3b);

    private final byte[] logoLeft;
    private final byte[] logoRight;

    public SelectionReportPdfSupport() {
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

    /** drawReportHeader (selectionReportsController.js:52-94) parity. */
    void drawReportHeader(Document doc, boolean isFirstPage, String yearOrCohort) throws DocumentException {
        PdfPTable headerRow = new PdfPTable(new float[]{1f, 4f, 1f});
        headerRow.setWidthPercentage(100);
        headerRow.addCell(logoCell(logoLeft));

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        Paragraph title = new Paragraph(TITLE, new Font(Font.TIMES_ROMAN, isFirstPage ? 18 : 12, Font.BOLD));
        title.setAlignment(Element.ALIGN_CENTER);
        Paragraph subtitle = new Paragraph("PRATIBHA POSHAK - " + yearOrCohort,
                new Font(Font.TIMES_ROMAN, isFirstPage ? 16 : 10, Font.NORMAL));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(title);
        titleCell.addElement(subtitle);
        headerRow.addCell(titleCell);
        headerRow.addCell(logoCell(logoRight));
        doc.add(headerRow);

        Font addressFont = new Font(Font.TIMES_ROMAN, isFirstPage ? 8 : 7, Font.NORMAL);
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
                // logo genuinely missing/corrupt -- omit silently, matching Node's fs.existsSync guard
            }
        }
        return cell;
    }

    /** Base64 chart image embed shared by all 4 portrait PDFs. Malformed images are skipped, not fatal. */
    void addChartImage(Document doc, Object chartImage, float width, float height) throws DocumentException {
        if (chartImage == null) return;
        String raw = String.valueOf(chartImage).replaceFirst("^data:image/\\w+;base64,", "");
        try {
            byte[] imgBytes = Base64.getDecoder().decode(raw);
            Image img = Image.getInstance(imgBytes);
            img.scaleToFit(width, height);
            img.setAlignment(Element.ALIGN_CENTER);
            doc.add(img);
            doc.add(Chunk.NEWLINE);
        } catch (Exception ignored) {
            // best-effort render, matches Node's lack of validation on chartImage content
        }
    }

    static void addHeaderCell(PdfPTable table, String text, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 10, Font.BOLD, color)));
        cell.setPadding(6f);
        table.addCell(cell);
    }

    static void addBodyCell(PdfPTable table, String text, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 10, Font.NORMAL, color)));
        cell.setPadding(6f);
        table.addCell(cell);
    }

    static String str(Object v) { return v == null ? "0" : String.valueOf(v); }

    @SuppressWarnings("unchecked")
    static void addDistrictSubheading(Document doc, Map<String, Object> item, String type) throws DocumentException {
        if (!"block".equals(type)) return;
        String districtName = String.valueOf(item.get("districtName")).toUpperCase();
        Font underlineBold = new Font(Font.HELVETICA, 12, Font.BOLD | Font.UNDERLINE);
        Paragraph p = new Paragraph("District: " + districtName, underlineBold);
        p.setSpacingAfter(10f);
        doc.add(p);
    }

    /** downloadNMMSPDF (selectionReportsController.js:97-172) parity. */
    @SuppressWarnings("unchecked")
    public GeneratedPdf buildNmmsPdf(Map<String, Object> body) throws DocumentException {
        String year = String.valueOf(body.get("year"));
        String type = String.valueOf(body.get("type"));
        List<Map<String, Object>> reportPayload = (List<Map<String, Object>>) body.getOrDefault("reportPayload", List.of());

        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        boolean isFirstPage = true;
        for (Map<String, Object> item : reportPayload) {
            if (!isFirstPage) doc.newPage();
            drawReportHeader(doc, isFirstPage, year);

            String mainTitle = "block".equals(type) ? "NMMS Report (by Block)" : "NMMS Report (by District)";
            Paragraph titlePara = new Paragraph(mainTitle, new Font(Font.HELVETICA, 14, Font.BOLD, TITLE_COLOR));
            titlePara.setAlignment(Element.ALIGN_CENTER);
            titlePara.setSpacingAfter(10f);
            doc.add(titlePara);

            addDistrictSubheading(doc, item, type);
            addChartImage(doc, item.get("chartImage"), 480f, 220f);

            PdfPTable table = new PdfPTable(new float[]{300f, 180f});
            table.setWidthPercentage(100);
            addHeaderCell(table, "district".equals(type) ? "District Name" : "Block Name", HEADER_CELL_COLOR);
            addHeaderCell(table, "Applicant Count", HEADER_CELL_COLOR);

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) item.getOrDefault("blocks", List.of());
            for (Map<String, Object> b : blocks) {
                addBodyCell(table, str(b.get("label")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("applicant_count")), BODY_CELL_COLOR);
            }
            doc.add(table);
            isFirstPage = false;
        }
        doc.close();

        String reportLabel = "block".equals(type) ? "Block_Report" : "District_Report";
        String filename = "NMMS_" + reportLabel + "_" + year + ".pdf";
        return new GeneratedPdf(out.toByteArray(), filename);
    }

    /** downloadTurnOutPDF (selectionReportsController.js:174-243) parity. Filename has a Date.now()-equivalent
     *  timestamp (unlike NMMS) -- Firm Decision 6: kept purely for the Content-Disposition filename, no disk write. */
    @SuppressWarnings("unchecked")
    public GeneratedPdf buildTurnoutPdf(Map<String, Object> body) throws DocumentException {
        String year = String.valueOf(body.get("year"));
        String type = String.valueOf(body.get("type"));
        List<Map<String, Object>> reportPayload = (List<Map<String, Object>>) body.getOrDefault("reportPayload", List.of());

        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        boolean isFirstPage = true;
        for (Map<String, Object> item : reportPayload) {
            if (!isFirstPage) doc.newPage();
            drawReportHeader(doc, isFirstPage, year);

            String mainTitle = "block".equals(type) ? "Test Turn-Out Report (by Block)" : "Test Turn-Out Report (by District)";
            Paragraph titlePara = new Paragraph(mainTitle, new Font(Font.HELVETICA, 14, Font.BOLD, TITLE_COLOR));
            titlePara.setAlignment(Element.ALIGN_CENTER);
            doc.add(titlePara);
            Paragraph subtitlePara = new Paragraph("(PP-Test appeared students as a percentage of called students)",
                    new Font(Font.HELVETICA, 10, Font.ITALIC, SUBTITLE_COLOR));
            subtitlePara.setAlignment(Element.ALIGN_CENTER);
            subtitlePara.setSpacingAfter(10f);
            doc.add(subtitlePara);

            addDistrictSubheading(doc, item, type);
            addChartImage(doc, item.get("chartImage"), 480f, 220f);

            PdfPTable table = new PdfPTable(new float[]{200f, 80f, 80f, 100f});
            table.setWidthPercentage(100);
            addHeaderCell(table, "district".equals(type) ? "District" : "Block", HEADER_CELL_COLOR);
            addHeaderCell(table, "Called", HEADER_CELL_COLOR);
            addHeaderCell(table, "Appeared", HEADER_CELL_COLOR);
            addHeaderCell(table, "Turn-Out %", HEADER_CELL_COLOR);

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) item.getOrDefault("blocks", List.of());
            for (Map<String, Object> b : blocks) {
                addBodyCell(table, str(b.get("label")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("called_count")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("appeared_count")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("turnout_percentage")) + "%", BODY_CELL_COLOR);
            }
            doc.add(table);
            isFirstPage = false;
        }
        doc.close();

        String reportLabel = "block".equals(type) ? "Block_TurnOut" : "District_TurnOut";
        String filename = "NMMS_" + reportLabel + "_" + year + "_" + System.currentTimeMillis() + ".pdf";
        return new GeneratedPdf(out.toByteArray(), filename);
    }

    /** downloadSelectionPDF (selectionReportsController.js:260-340) parity. Same timestamped-filename pattern as Turn-Out. */
    @SuppressWarnings("unchecked")
    public GeneratedPdf buildSelectionPdf(Map<String, Object> body) throws DocumentException {
        String year = String.valueOf(body.get("year"));
        String type = String.valueOf(body.get("type"));
        List<Map<String, Object>> reportPayload = (List<Map<String, Object>>) body.getOrDefault("reportPayload", List.of());

        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        boolean isFirstPage = true;
        for (Map<String, Object> item : reportPayload) {
            if (!isFirstPage) doc.newPage();
            drawReportHeader(doc, isFirstPage, year);

            Paragraph titlePara = new Paragraph("Selection Success Report", new Font(Font.HELVETICA, 14, Font.BOLD, TITLE_COLOR));
            titlePara.setAlignment(Element.ALIGN_CENTER);
            doc.add(titlePara);
            Paragraph subtitlePara = new Paragraph("(Percentage of appeared students successfully selected)",
                    new Font(Font.HELVETICA, 10, Font.ITALIC, SUBTITLE_COLOR));
            subtitlePara.setAlignment(Element.ALIGN_CENTER);
            subtitlePara.setSpacingAfter(10f);
            doc.add(subtitlePara);

            addDistrictSubheading(doc, item, type);
            addChartImage(doc, item.get("chartImage"), 480f, 220f);

            PdfPTable table = new PdfPTable(new float[]{200f, 90f, 90f, 100f});
            table.setWidthPercentage(100);
            addHeaderCell(table, "district".equals(type) ? "District" : "Block", HEADER_CELL_COLOR);
            addHeaderCell(table, "Appeared", HEADER_CELL_COLOR);
            addHeaderCell(table, "Selected", HEADER_CELL_COLOR);
            addHeaderCell(table, "Success %", HEADER_CELL_COLOR);

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) item.getOrDefault("blocks", List.of());
            for (Map<String, Object> b : blocks) {
                addBodyCell(table, str(b.get("label")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("appeared_count")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("selected_count")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("selection_percentage")) + "%", BODY_CELL_COLOR);
            }
            doc.add(table);
            isFirstPage = false;
        }
        doc.close();

        String reportLabel = "block".equals(type) ? "Block_Selection" : "District_Selection";
        String filename = "NMMS_" + reportLabel + "_" + year + "_" + System.currentTimeMillis() + ".pdf";
        return new GeneratedPdf(out.toByteArray(), filename);
    }

    /** downloadSelectsPDF (selectionReportsController.js:354-427) parity. No timestamp in the filename
     *  (matches Node exactly -- ground truth §7 quirk 3 flagged this as an inconsistency vs Turn-Out/
     *  Selection, but it's preserved as-is since it's a client-visible filename string, not an archive path). */
    @SuppressWarnings("unchecked")
    public GeneratedPdf buildSelectsPdf(Map<String, Object> body) throws DocumentException {
        String year = String.valueOf(body.get("year"));
        String type = String.valueOf(body.get("type"));
        List<Map<String, Object>> reportPayload = (List<Map<String, Object>>) body.getOrDefault("reportPayload", List.of());

        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        boolean isFirstPage = true;
        for (Map<String, Object> item : reportPayload) {
            if (!isFirstPage) doc.newPage();
            drawReportHeader(doc, isFirstPage, year);

            String mainTitle = "block".equals(type) ? "Selects Report (by Block)" : "Selects Report (by District)";
            Paragraph titlePara = new Paragraph(mainTitle, new Font(Font.HELVETICA, 14, Font.BOLD, TITLE_COLOR));
            titlePara.setAlignment(Element.ALIGN_CENTER);
            doc.add(titlePara);
            Paragraph subtitlePara = new Paragraph("(Gender-wise selection details)",
                    new Font(Font.HELVETICA, 10, Font.ITALIC, SUBTITLE_COLOR));
            subtitlePara.setAlignment(Element.ALIGN_CENTER);
            subtitlePara.setSpacingAfter(6f);
            doc.add(subtitlePara);

            addDistrictSubheading(doc, item, type);
            addChartImage(doc, item.get("chartImage"), 480f, 220f);

            PdfPTable table = new PdfPTable(new float[]{220f, 130f, 130f});
            table.setWidthPercentage(100);
            addHeaderCell(table, "Location Name", HEADER_CELL_COLOR);
            addHeaderCell(table, "Boys Selected", HEADER_CELL_COLOR);
            addHeaderCell(table, "Girls Selected", HEADER_CELL_COLOR);

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) item.getOrDefault("blocks", List.of());
            for (Map<String, Object> b : blocks) {
                addBodyCell(table, str(b.get("label")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("boys_sel")), BODY_CELL_COLOR);
                addBodyCell(table, str(b.get("girls_sel")), BODY_CELL_COLOR);
            }
            doc.add(table);
            isFirstPage = false;
        }
        doc.close();

        String reportLabel = "block".equals(type) ? "Block_Selects" : "District_Selects";
        String filename = "NMMS_" + reportLabel + "_" + year + ".pdf";
        return new GeneratedPdf(out.toByteArray(), filename);
    }
}
