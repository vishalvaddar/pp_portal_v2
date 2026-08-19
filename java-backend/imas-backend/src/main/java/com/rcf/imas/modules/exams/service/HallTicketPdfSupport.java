package com.rcf.imas.modules.exams.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenPDF port of examControllers.js generateStudentPDF (Firm Decision 10). Reproduces the FUNCTIONAL content
 * verbatim -- all data fields, the hardcoded institutional strings (incl. "PRATIBHA POSHAK EXAMINATION - 2026"),
 * the 4 signature boxes, the Kannada instructions block with the embedded TTF -- as a simple top-down flow
 * document (Paragraphs/PdfPTables), NOT a pixel-for-pixel clone of pdfkit's absolute x/y layout. Kannada shaping
 * fidelity is best-effort (documented risk -- verify visually against a Node-generated reference PDF).
 */
@Component
public class HallTicketPdfSupport {

    private static final String HEADER_TITLE = "RAJALAKSHMI CHILDREN FOUNDATION";
    private static final String HEADER_SUBTITLE = "PRATIBHA POSHAK EXAMINATION - 2026";
    private static final String HEADER_ADDRESS = "Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Near Kolhapur Circle, Belagavi 590016";
    private static final String HEADER_CONTACT = "Contact No. +91 9444900755, +91 9606930208";

    private static final String[] KANNADA_INSTRUCTIONS = {
        "೧) ವಿದ್ಯಾರ್ಥಿಗಳು ತಮ್ಮ ಆಧಾರ್ ಕಾರ್ಡ್ ಫೋಟೋಕಾಪಿ ಮತ್ತು ಇತ್ತೀಚಿನ ಪಾಸ್ಪೋರ್ಟ್ ಗಾತ್ರದ ಒಂದು ಫೋಟೋ ಕಡ್ಡಾಯವಾಗಿ ತರಬೇಕು.",
        "೨) ದಯವಿಟ್ಟು ನಿಮ್ಮ ಜ್ಯಾಮೆಟ್ರಿ ಬಾಕ್ಸ್, ಪೆನ್ ಮತ್ತು ಪರೀಕ್ಷಾ ಪ್ಯಾಡ್ ತರಬೇಕು.",
        "೩) ವಿದ್ಯಾರ್ಥಿಗಳು ಪರೀಕ್ಷಾ ಕೇಂದ್ರಕ್ಕೆ ನಿಗದಿತ ಸಮಯಕ್ಕಿಂತ ಕನಿಷ್ಠ ೩೦ ನಿಮಿಷಗಳ ಮುಂಚಿತವಾಗಿ ಆಗಮಿಸಬೇಕು.",
        "೪) ಮೊಬೈಲ್, ಟ್ಯಾಬ್, ಸ್ಮಾರ್ಟ್ ವಾಚ್ ಮತ್ತು ಇತರ ಎಲೆಕ್ಟ್ರಾನಿಕ್ ಸಾಧನಗಳು ನಿಷೇಧಿತ.",
        "೫) ವಿದ್ಯಾರ್ಥಿಗಳು ಪರೀಕ್ಷೆಯ ವೇಳೆ ಮೇಲ್ವಿಚಾರಕರ ಸೂಚನೆಗಳನ್ನು ಅನುಸರಿಸಬೇಕು.",
        "೬) ಇತರರಿಗೆ ಅಡ್ಡಿಪಡಿಸದಂತೆ ಪರೀಕ್ಷೆಯ ಅವಧಿಯಲ್ಲಿ ಮೌನವನ್ನು ಕಾಪಾಡಿ.",
        "೭) ಯಾವುದೇ ರೀತಿಯ ನಕಲು (ಚೀಟಿ) ಕಂಡುಬಂದಲ್ಲಿ, ವಿದ್ಯಾರ್ಥಿಯನ್ನು ತಕ್ಷಣವೇ ಆನರ್ಹಗೊಳಿಸಲಾಗುವುದು.",
        "೮) ಪರೀಕ್ಷೆಯ ಸಮಯದಲ್ಲಿ ವಿದ್ಯಾರ್ಥಿಗಳ ಮಧ್ಯೆ ಸಂಭಾಷಣೆ ಅನುಮತಿ ಇಲ್ಲ.",
        "೯) ಸಹಾಯ ಬೇಕಾದರೆ ಅಥವಾ ಅನುಮಾನ ಇದ್ದರೆ, ಕೈ ಎತ್ತಿ ಮೇಲ್ವಿಚಾರಕರ ಸಹಾಯಕ್ಕಾಗಿ ಕೇಳಬೇಕು."
    };

    private final byte[] logoLeft;
    private final byte[] logoRight;
    private final byte[] kannadaTtf;
    private final byte[] authoritySignature;
    private final byte[] stamp;

    public HallTicketPdfSupport() {
        this.logoLeft = readClasspathBytes("exam-assets/rcf_logo-removebg-preview.png");
        this.logoRight = readClasspathBytes("exam-assets/logo.png");
        this.kannadaTtf = readClasspathBytes("exam-assets/NotoSansKannada-Regular.ttf");
        this.authoritySignature = readClasspathBytes("exam-assets/ravi_sir_sign-removebg-preview.png");
        this.stamp = readClasspathBytes("exam-assets/rcf_stamp-removebg-preview.png");
    }

    private static byte[] readClasspathBytes(String path) {
        try {
            return new ClassPathResource(path).getInputStream().readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Missing required exam-asset resource: " + path, e);
        }
    }

    public byte[] build(Map<String, Object> student) {
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            BaseFont kannadaBase = BaseFont.createFont("NotoSansKannada-Regular.ttf", BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, kannadaTtf, null);
            Font kannadaTitleFont = new Font(kannadaBase, 16);
            Font kannadaBodyFont = new Font(kannadaBase, 10);

            addHeader(doc);
            addHallTicketTitle(doc);
            addStudentDetails(doc, student);
            addExamCentreDetails(doc, student);
            addExamDateAndReportingTime(doc, student);
            addKannadaInstructions(doc, kannadaTitleFont, kannadaBodyFont);
            addSignatureBoxes(doc);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addHeader(Document doc) throws DocumentException {
        PdfPTable headerRow = new PdfPTable(new float[]{1f, 4f, 1f});
        headerRow.setWidthPercentage(100);
        headerRow.addCell(borderlessImageCell(logoLeft));
        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        Paragraph title = new Paragraph(HEADER_TITLE, new Font(Font.HELVETICA, 18, Font.BOLD));
        title.setAlignment(Element.ALIGN_CENTER);
        Paragraph subtitle = new Paragraph(HEADER_SUBTITLE, new Font(Font.HELVETICA, 16, Font.BOLD));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(title);
        titleCell.addElement(subtitle);
        headerRow.addCell(titleCell);
        headerRow.addCell(borderlessImageCell(logoRight));
        doc.add(headerRow);

        Paragraph address = new Paragraph(HEADER_ADDRESS, new Font(Font.HELVETICA, 8));
        address.setAlignment(Element.ALIGN_CENTER);
        Paragraph contact = new Paragraph(HEADER_CONTACT, new Font(Font.HELVETICA, 8));
        contact.setAlignment(Element.ALIGN_CENTER);
        contact.setSpacingAfter(10f);
        doc.add(address);
        doc.add(contact);
    }

    private PdfPCell borderlessImageCell(byte[] imageBytes) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        try {
            Image img = Image.getInstance(imageBytes);
            img.scaleToFit(70, 70);
            cell.addElement(img);
        } catch (Exception e) {
            // logo genuinely missing/corrupt -- omit silently, matching Node's `if (fs.existsSync(...))` guard
        }
        return cell;
    }

    private void addHallTicketTitle(Document doc) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_CENTER);
        PdfPCell cell = new PdfPCell(new Phrase("HALL TICKET", new Font(Font.HELVETICA, 24, Font.BOLD)));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(10f);
        table.addCell(cell);
        doc.add(table);
    }

    private void addStudentDetails(Document doc, Map<String, Object> student) throws DocumentException {
        PdfPTable outer = new PdfPTable(new float[]{3f, 1f});
        outer.setWidthPercentage(100);
        outer.setSpacingBefore(10f);

        PdfPCell details = new PdfPCell();
        details.setPadding(8f);
        details.addElement(new Paragraph("STUDENT DETAILS", new Font(Font.HELVETICA, 14, Font.BOLD)));
        details.addElement(fieldLine("Name:", str(student.get("student_name"))));
        details.addElement(fieldLine("Hall Ticket No:", str(student.get("pp_hall_ticket_no"))));
        details.addElement(fieldLine("NMMS Register No:", str(student.get("nmms_reg_number"))));
        outer.addCell(details);

        PdfPCell photo = new PdfPCell(new Phrase("Passport Photo\n3.5cm x 4.5cm", new Font(Font.HELVETICA, 8)));
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        photo.setFixedHeight(110f);
        outer.addCell(photo);

        doc.add(outer);
    }

    private Paragraph fieldLine(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", new Font(Font.HELVETICA, 12, Font.BOLD)));
        p.add(new Chunk(value == null || value.isBlank() ? "N/A" : value, new Font(Font.HELVETICA, 12)));
        return p;
    }

    private void addExamCentreDetails(Document doc, Map<String, Object> student) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10f);
        PdfPCell cell = new PdfPCell();
        cell.setPadding(8f);
        cell.addElement(new Paragraph("Exam Center Details:", new Font(Font.HELVETICA, 10, Font.BOLD)));
        cell.addElement(new Paragraph(orDefault(str(student.get("pp_exam_centre_name")), "Exam Center"),
                new Font(Font.HELVETICA, 10, Font.BOLD)));

        java.util.List<String> parts = new java.util.ArrayList<>();
        if (notBlank(student.get("address"))) parts.add(str(student.get("address")));
        if (notBlank(student.get("village"))) parts.add(str(student.get("village")));
        if (notBlank(student.get("pincode"))) parts.add(str(student.get("pincode")));
        String fullAddress = parts.isEmpty() ? "Address not available" : String.join(", ", parts);

        Object lat = student.get("latitude");
        Object lng = student.get("longitude");
        if (notBlank(lat) && notBlank(lng)) {
            Anchor link = new Anchor(fullAddress, new Font(Font.HELVETICA, 9, Font.UNDERLINE, java.awt.Color.BLUE));
            link.setReference("https://www.google.com/maps?q=" + str(lat) + "," + str(lng));
            cell.addElement(new Paragraph(link));
        } else {
            cell.addElement(new Paragraph(fullAddress, new Font(Font.HELVETICA, 9)));
        }
        table.addCell(cell);
        doc.add(table);
    }

    private void addExamDateAndReportingTime(Document doc, Map<String, Object> student) throws DocumentException {
        String formattedExamDateTime = formatDate(str(student.get("exam_date"))) + ", "
                + formatTimeManual(str(student.get("exam_start_time"))) + " to " + formatTimeManual(str(student.get("exam_end_time")));

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10f);

        PdfPCell dateCell = new PdfPCell();
        dateCell.setPadding(8f);
        dateCell.addElement(new Paragraph("Exam Date & Time", new Font(Font.HELVETICA, 14, Font.BOLD)));
        dateCell.addElement(new Paragraph(formattedExamDateTime, new Font(Font.HELVETICA, 12)));
        table.addCell(dateCell);

        PdfPCell reportingCell = new PdfPCell();
        reportingCell.setPadding(8f);
        reportingCell.addElement(new Paragraph("Reporting Time", new Font(Font.HELVETICA, 14, Font.BOLD)));
        reportingCell.addElement(new Paragraph(formatTimeManual(str(student.get("exam_start_time"))), new Font(Font.HELVETICA, 12)));
        table.addCell(reportingCell);

        doc.add(table);
    }

    private void addKannadaInstructions(Document doc, Font titleFont, Font bodyFont) throws DocumentException {
        Paragraph title = new Paragraph("ಸೂಚನೆಗಳು", titleFont); // "Instructions" (Kannada, verbatim)
        title.setSpacingBefore(12f);
        doc.add(title);
        LineSeparator line = new LineSeparator();
        doc.add(new Chunk(line));

        Paragraph instructions = new Paragraph();
        instructions.setSpacingBefore(6f);
        for (String line1 : KANNADA_INSTRUCTIONS) {
            instructions.add(new Paragraph(line1, bodyFont));
        }
        doc.add(instructions);
    }

    private void addSignatureBoxes(Document doc) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingBefore(20f);

        table.addCell(signatureCell("Authority Signature", authoritySignature, 60f, 25f));
        table.addCell(signatureCell("Invigilator Signature", null, 0, 0));
        table.addCell(signatureCell("Student Signature", null, 0, 0));
        table.addCell(signatureCell("Official Seal", stamp, 60f, 45f));

        doc.add(table);
    }

    private PdfPCell signatureCell(String label, byte[] imageBytes, float w, float h) {
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(60f);
        cell.setPadding(6f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph p = new Paragraph(label, new Font(Font.HELVETICA, 10, Font.BOLD));
        p.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p);
        if (imageBytes != null) {
            try {
                Image img = Image.getInstance(imageBytes);
                img.scaleToFit(w, h);
                cell.addElement(img);
            } catch (Exception ignored) {
                // signature/stamp genuinely missing -- omit silently, matching Node's fs.existsSync guard
            }
        }
        return cell;
    }

    private static boolean notBlank(Object v) { return v != null && !String.valueOf(v).isBlank(); }
    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static String orDefault(String v, String def) { return (v == null || v.isBlank()) ? def : v; }

    /** formatDate(dateString) parity (examControllers.js:1503-1515): DD-MM-YYYY, zero-padded. */
    static String formatDate(String dateString) {
        if (dateString == null || dateString.isBlank()) return "N/A";
        try {
            LocalDate d = LocalDate.parse(dateString.length() > 10 ? dateString.substring(0, 10) : dateString);
            return DateTimeFormatter.ofPattern("dd-MM-yyyy").format(d);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private static final Pattern TIME_RE = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?");

    /** formatTimeManual(timeStr) parity (examControllers.js:1518-1532): 12-hour AM/PM. */
    static String formatTimeManual(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return "N/A";
        Matcher m = TIME_RE.matcher(timeStr);
        if (!m.find()) return timeStr;
        int hh = Integer.parseInt(m.group(1));
        String mm = m.group(2);
        String ampm = hh >= 12 ? "PM" : "AM";
        hh = hh % 12;
        if (hh == 0) hh = 12;
        return String.format("%02d:%s %s", hh, mm, ampm);
    }
}
