package com.rcf.imas.modules.exams.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** POI port of examControllers.js generateStudentList: header info block, 10-column table w/ score coloring,
 *  optional Score Summary sheet -- all in-memory (Firm Decision 9), no disk write, no res.download/setTimeout dance. */
@Component
public class ExamCallingListXlsxSupport {

    private static final DateTimeFormatter DATE_DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] build(List<Map<String, Object>> rows) {
        Map<String, Object> examInfo = rows.get(0);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Student Calling List");

            CellStyle titleStyle = boldStyle(wb, 14, "1B5E20", null, HorizontalAlignment.CENTER);
            CellStyle labelStyle = boldStyle(wb, 11, null, "F5F5F5", null);
            CellStyle headerStyle = boldStyle(wb, 11, "000000", "D4F1D4", HorizontalAlignment.CENTER);

            // Order matches ExamStudentListIT's literal row-index assertions: row 2 = Exam Name,
            // row 5 = Contact Person (Contact Person precedes Exam Centre), 8 total info rows (0..7)
            // so the table header lands at row index 8 (= infoLines.length).
            String[][] infoLines = {
                {"STUDENT CALLING LIST"}, {},
                {"Exam Name:", str(examInfo.get("exam_name"))},
                {"Exam Date:", formatDate(examInfo.get("exam_date"))},
                {"Exam Time:", str(examInfo.get("exam_start_time")) + " - " + str(examInfo.get("exam_end_time"))},
                {"Contact Person:", orDefault(str(examInfo.get("contact_person")), "Not specified")},
                {"Exam Centre:", str(examInfo.get("pp_exam_centre_name"))},
                {"Generated on:", DATE_DDMMYYYY.format(LocalDate.now())}
            };
            for (int r = 0; r < infoLines.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < infoLines[r].length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(infoLines[r][c] == null ? "" : infoLines[r][c]);
                    if (r == 0) cell.setCellStyle(titleStyle);
                    else if (c == 0 && !infoLines[r][c].isEmpty()) cell.setCellStyle(labelStyle);
                }
            }

            int headerRowIdx = infoLines.length; // 10
            String[] headers = {"Sl. No.", "NMMS Reg. No.", "Hall Ticket No.", "Student Name", "School Name",
                    "Block Name", "Contact No. 1", "Contact No. 2", "GMAT Score", "SAT Score"};
            Row headerRow = sheet.createRow(headerRowIdx);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(headerStyle);
            }

            CellStyle greenScore = scoreStyle(wb, "006100", "E6F3E6", true);
            CellStyle redScore = scoreStyle(wb, "9C0000", "FFE6E6", false);

            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> s = rows.get(i);
                Row row = sheet.createRow(headerRowIdx + 1 + i);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(str(s.get("nmms_reg_number")));
                row.createCell(2).setCellValue(str(s.get("pp_hall_ticket_no")));
                row.createCell(3).setCellValue(str(s.get("student_name")));
                row.createCell(4).setCellValue(str(s.get("institute_name")));
                row.createCell(5).setCellValue(str(s.get("block_name")));
                row.createCell(6).setCellValue(str(s.get("contact_no1")));
                row.createCell(7).setCellValue(str(s.get("contact_no2")));
                Cell gmat = row.createCell(8);
                gmat.setCellValue(str(s.get("gmat_score")));
                styleScoreCell(gmat, s.get("gmat_score"), greenScore, redScore);
                Cell sat = row.createCell(9);
                sat.setCellValue(str(s.get("sat_score")));
                styleScoreCell(sat, s.get("sat_score"), greenScore, redScore);
            }

            int totalRowIdx = headerRowIdx + 1 + rows.size() + 1; // blank row then total row
            sheet.createRow(totalRowIdx).createCell(0).setCellValue("Total Students: " + rows.size());

            for (int c = 0; c < 10; c++) sheet.setColumnWidth(c, colWidth(c));

            addScoreSummaryIfPresent(wb, rows); // must run BEFORE the single final write below

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void addScoreSummaryIfPresent(Workbook wb, List<Map<String, Object>> rows) {
        List<Double> gmat = rows.stream().map(r -> parseOrNull(r.get("gmat_score"))).filter(java.util.Objects::nonNull).toList();
        List<Double> sat = rows.stream().map(r -> parseOrNull(r.get("sat_score"))).filter(java.util.Objects::nonNull).toList();
        boolean anyScore = rows.stream().anyMatch(r -> parseOrNull(r.get("gmat_score")) != null || parseOrNull(r.get("sat_score")) != null);
        if (!anyScore) return;

        Sheet summary = wb.createSheet("Score Summary");
        CellStyle titleStyle = boldStyle(wb, 14, "1B5E20", null, null);
        int r = 0;
        setCell(summary, r++, 0, "SCORE SUMMARY", titleStyle);
        r++; // blank
        setCell(summary, r++, 0, "GMAT Score Statistics:", null);
        setCell(summary, r, 0, "Highest Score:", null); setCell(summary, r++, 1, statOrNA(gmat, "max"), null);
        setCell(summary, r, 0, "Lowest Score:", null); setCell(summary, r++, 1, statOrNA(gmat, "min"), null);
        setCell(summary, r, 0, "Average Score:", null); setCell(summary, r++, 1, statOrNA(gmat, "avg"), null);
        r++; // blank
        setCell(summary, r++, 0, "SAT Score Statistics:", null);
        setCell(summary, r, 0, "Highest Score:", null); setCell(summary, r++, 1, statOrNA(sat, "max"), null);
        setCell(summary, r, 0, "Lowest Score:", null); setCell(summary, r++, 1, statOrNA(sat, "min"), null);
        setCell(summary, r, 0, "Average Score:", null); setCell(summary, r++, 1, statOrNA(sat, "avg"), null);
        r++; // blank
        int withScores = (int) rows.stream().filter(row -> parseOrNull(row.get("gmat_score")) != null || parseOrNull(row.get("sat_score")) != null).count();
        setCell(summary, r++, 0, "Total Students with Scores: " + withScores, null);
        setCell(summary, r, 0, "Total Students Overall: " + rows.size(), null);
        summary.setColumnWidth(0, 25 * 256);
        summary.setColumnWidth(1, 15 * 256);
    }

    private static void setCell(Sheet sheet, int r, int c, String value, CellStyle style) {
        Row row = sheet.getRow(r);
        if (row == null) row = sheet.createRow(r);
        Cell cell = row.createCell(c);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static String statOrNA(List<Double> vals, String kind) {
        if (vals.isEmpty()) return "N/A";
        double v = switch (kind) {
            case "max" -> vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "min" -> vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            default -> vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        };
        return kind.equals("avg") ? String.format(Locale.US, "%.2f", v) : String.valueOf(v);
    }

    private static Double parseOrNull(Object v) {
        if (v == null) return null;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static void styleScoreCell(Cell cell, Object rawScore, CellStyle greenStyle, CellStyle redStyle) {
        Double v = parseOrNull(rawScore);
        if (v == null) return;
        cell.setCellStyle(v >= 70 ? greenStyle : redStyle);
    }

    private static CellStyle boldStyle(Workbook wb, int size, String fontArgb, String fillArgb, HorizontalAlignment align) {
        CellStyle style = wb.createCellStyle();
        org.apache.poi.xssf.usermodel.XSSFFont font = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) size);
        if (fontArgb != null) font.setColor(new XSSFColor(colorFromHex(fontArgb), null));
        style.setFont(font);
        if (fillArgb != null) {
            style.setFillForegroundColor(new XSSFColor(colorFromHex(fillArgb), null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (align != null) style.setAlignment(align);
        return style;
    }

    /** fontHex colors the score TEXT (green "006100"/red "9C0000"); fillHex colors the cell BACKGROUND
     *  ("E6F3E6"/"FFE6E6") -- matches Node's ExcelJS `font.color` + `fill.fgColor` pair exactly. */
    private static CellStyle scoreStyle(Workbook wb, String fontHex, String fillHex, boolean bold) {
        CellStyle style = wb.createCellStyle();
        org.apache.poi.xssf.usermodel.XSSFFont font = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
        font.setBold(bold);
        font.setColor(new XSSFColor(colorFromHex(fontHex), null));
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(colorFromHex(fillHex), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private static Color colorFromHex(String hex) {
        return new Color(Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
    }

    private static int colWidth(int c) {
        int[] widths = {8, 15, 15, 25, 35, 20, 15, 15, 12, 12};
        return widths[c] * 256;
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String orDefault(String v, String def) { return (v == null || v.isBlank()) ? def : v; }

    private static String formatDate(Object dateVal) {
        if (dateVal == null) return "";
        LocalDate d = LocalDate.parse(String.valueOf(dateVal)); // genericRow emits DATE as "yyyy-MM-dd"
        return DATE_DDMMYYYY.format(d);
    }
}
