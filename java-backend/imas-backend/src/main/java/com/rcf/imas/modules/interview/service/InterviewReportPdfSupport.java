package com.rcf.imas.modules.interview.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * OpenPDF port of interviewController.js downloadAssignmentReport's pdfkit output (Firm Decision 6). In-memory
 * (ByteArrayOutputStream), NO disk write (Node also pipes a permanent copy to GENERATED_FILES_ROOT — dropped here,
 * mirroring shortlist's stateless download-data decision). One page per student. Functional/readable layout, NOT a
 * pixel clone. Text-only header (institutional strings verbatim); logos dropped as a simplification.
 */
@Component
public class InterviewReportPdfSupport {

    private static final Font TITLE = new Font(Font.TIMES_ROMAN, 18, Font.BOLD);
    private static final Font SUB = new Font(Font.TIMES_ROMAN, 12, Font.NORMAL);
    private static final Font SMALL = new Font(Font.TIMES_ROMAN, 8, Font.NORMAL);
    private static final Font STUDENT_TITLE = new Font(Font.TIMES_ROMAN, 16, Font.BOLD);
    private static final Font SECTION = new Font(Font.TIMES_ROMAN, 12, Font.BOLD);
    private static final Font LABEL = new Font(Font.TIMES_ROMAN, 10, Font.NORMAL);
    private static final Font VALUE = new Font(Font.TIMES_ROMAN, 10, Font.BOLD);
    private static final Font GRAY = new Font(Font.TIMES_ROMAN, 10, Font.BOLD, java.awt.Color.GRAY);

    /** 29 label/field pairs of the "Primary Applicant & Profile Details" block (interviewController.js:217-247). */
    private static final String[][] PROFILE_FIELDS = {
        {"Current School:", "Current School Name"}, {"Previous School:", "Previous School Name"},
        {"State:", "State Name"}, {"District:", "District Name"}, {"Block:", "Block Name"},
        {"Village:", "village"}, {"PP Exam Score:", "pp_exam_score"}, {"GMAT Score:", "gmat_score"},
        {"SAT Score:", "sat_score"}, {"Contact No 1:", "Contact No 1"}, {"Contact No 2:", "Contact No 2"},
        {"Father's Occupation:", "father_occupation"}, {"Mother's Occupation:", "mother_occupation"},
        {"Father's Education:", "father_education"}, {"Mother's Education:", "mother_education"},
        {"Household Size:", "household_size"}, {"Own House:", "own_house"}, {"Smart Phone Home:", "smart_phone_home"},
        {"Internet Facility:", "internet_facility_home"}, {"Career Goals:", "career_goals"},
        {"Subjects of Interest:", "subjects_of_interest"}, {"Transportation Mode:", "transportation_mode"},
        {"Distance to School:", "distance_to_school"}, {"Two Wheelers:", "num_two_wheelers"},
        {"Four Wheelers:", "num_four_wheelers"}, {"Irrigation Land:", "irrigation_land"},
        {"Neighbor Name:", "neighbor_name"}, {"Favorite Teacher:", "favorite_teacher_name"},
        {"Assigned Interviewer:", "Assigned Interviewer Name"},
    };

    public byte[] build(String nmmsYear, List<Map<String, Object>> students) {
        Document doc = new Document(PageSize.A4, 30, 30, 100, 30);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();
            drawHeader(doc, nmmsYear);
            doc.add(spaced(new Paragraph("Interview Assignment", SUB), 4f));
            doc.add(spaced(new Paragraph("Assigned Student Details:", SECTION), 8f));

            for (int index = 0; index < students.size(); index++) {
                Map<String, Object> student = students.get(index);
                if (index > 0) { doc.newPage(); drawHeader(doc, nmmsYear); }
                renderStudent(doc, student);
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }
    }

    private void drawHeader(Document doc, String nmmsYear) throws DocumentException {
        doc.add(centered(new Paragraph("RAJALAKSHMI CHILDREN FOUNDATION", TITLE)));
        doc.add(centered(new Paragraph("PRATIBHA POSHAK EXAMINATION - " + safe(nmmsYear), SUB)));
        doc.add(centered(new Paragraph("Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Near Kolhapur Circle, Belagavi 590016", SMALL)));
        doc.add(centered(new Paragraph("Contact No. +91 9444900755, +91 9606930208", SMALL)));
        doc.add(new Chunk(new com.lowagie.text.pdf.draw.LineSeparator()));
    }

    @SuppressWarnings("unchecked")
    private void renderStudent(Document doc, Map<String, Object> student) throws DocumentException {
        doc.add(spaced(new Paragraph("Student Interview Report: " + safe(student.get("Student Name")), STUDENT_TITLE), 6f));
        doc.add(spaced(new Paragraph("Primary Applicant & Profile Details", SECTION), 4f));
        for (String[] f : PROFILE_FIELDS) doc.add(labelValue(f[0], safe(student.get(f[1]))));

        Map<String, Object> pending = (Map<String, Object>) student.get("Pending Assignment");
        List<Map<String, Object>> completed = (List<Map<String, Object>>) student.getOrDefault("Completed Rounds", List.of());

        if (pending != null) {
            doc.add(spaced(new Paragraph("Current Assignment Details", SECTION), 8f));
            doc.add(labelValue("Round:", orNA(safe(pending.get("Interview Round")))));
            doc.add(labelValue("Status:", safe(pending.get("Assignment Status"))));
            doc.add(labelValue("Interviewer:", safe(pending.get("Assigned Interviewer Name"))));
        }

        if (!completed.isEmpty()) {
            doc.add(spaced(new Paragraph("Completed Interview Results (" + completed.size() + " Round"
                    + (completed.size() > 1 ? "s" : "") + ")", SECTION), 8f));
            for (Map<String, Object> r : completed) {
                doc.add(spaced(new Paragraph("Result - " + safe(r.get("Interview Result")), VALUE), 4f));
                doc.add(labelValue("Interviewer:", safe(r.get("Assigned Interviewer Name"))));
                doc.add(labelValue("Date:", formatDate(safe(r.get("Interview Date")))));
                doc.add(labelValue("Mode:", safe(r.get("Interview Mode"))));
                doc.add(labelValue("Assignment Status:", safe(r.get("Assignment Status"))));
                doc.add(spaced(new Paragraph("--- Scores ---", new Font(Font.TIMES_ROMAN, 10, Font.BOLD)), 3f));
                doc.add(labelValue("Life Goals & Zeal:", safe(r.get("Life Goals and Zeal"))));
                doc.add(labelValue("Commitment to Learning:", safe(r.get("Commitment to Learning"))));
                doc.add(labelValue("Integrity:", safe(r.get("Integrity"))));
                doc.add(labelValue("Communication Skills:", safe(r.get("Communication Skills"))));
            }
        }

        if (pending == null && completed.isEmpty()) {
            doc.add(spaced(new Paragraph("No current assignment or completed interview records found.", GRAY), 8f));
        }
    }

    private static Paragraph labelValue(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", LABEL));
        p.add(new Chunk(value, VALUE));
        p.setSpacingBefore(2f);
        return p;
    }
    private static Paragraph centered(Paragraph p) { p.setAlignment(Element.ALIGN_CENTER); return p; }
    private static Paragraph spaced(Paragraph p, float before) { p.setSpacingBefore(before); return p; }
    private static String orNA(String v) { return (v == null || v.isEmpty()) ? "N/A" : v; }

    /** cleanText parity: N/A for null; strip control chars. */
    private static String safe(Object o) {
        if (o == null) return "N/A";
        String s = String.valueOf(o).replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
        return s.isEmpty() ? "N/A" : s;
    }

    /** formatDateForPdf parity: "d MMM yyyy" (en-IN short), fallback to the raw string. */
    private static String formatDate(String dateString) {
        if (dateString == null || "N/A".equals(dateString)) return "N/A";
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(dateString.length() > 10 ? dateString.substring(0, 10) : dateString);
            return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("en-IN")).format(d);
        } catch (Exception e) {
            return dateString;
        }
    }
}
