package com.rcf.imas.modules.results.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the two Results-module XLSX exports (POI equivalent of the Node ExcelJS workbooks). */
@Component
public class ResultsXlsxSupport {

    private static final List<String> SHARED_21_HEADERS = List.of(
        "Applicant ID", "NMMS Number", "Student Name", "Father Name", "GMAT Score", "SAT Score",
        "PP Exam Score", "PP Exam Cleared", "Interview Status", "Interview Result", "Interview Remarks",
        "Verification Status", "Verification Remarks", "Rejection Reasons", "Contact Number",
        "School DISE Code", "Medium", "School Name", "Division", "District", "Block");

    private static final DateTimeFormatter EXAM_CELL_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);
    private static final DateTimeFormatter EXAM_FILENAME_DATE = DateTimeFormatter.ofPattern("yyyy_MM_dd");

    /** downloadByBlocks: sheet "Results", fill FFE6E6FA, 21 columns. */
    public byte[] buildResultsSheet(List<Map<String, Object>> rows) {
        return build("Results", SHARED_21_HEADERS, "FFE6E6FA", rows, this::sharedRowValues);
    }

    /** downloadByExam: sheet "Exam Results", fill FFE6F5E6, 23 columns (shared 21 + Exam Name/Exam Date). */
    public byte[] buildExamResultsSheet(List<Map<String, Object>> rows) {
        List<String> headers = new java.util.ArrayList<>(SHARED_21_HEADERS);
        headers.add("Exam Name");
        headers.add("Exam Date");
        return build("Exam Results", headers, "FFE6F5E6", rows, r -> {
            List<Object> vals = new java.util.ArrayList<>(sharedRowValues(r));
            vals.add(orNA(r.get("exam_name")));
            Object examDate = r.get("exam_date");
            LocalDate d = asLocalDate(examDate);
            vals.add(d == null ? "N/A" : EXAM_CELL_DATE.format(d));
            return vals;
        });
    }

    private List<Object> sharedRowValues(Map<String, Object> r) {
        return List.of(
            emptyIfNull(r.get("applicant_id")),
            emptyIfNull(r.get("nmms_reg_number")),
            emptyIfNull(r.get("student_name")),
            emptyIfNull(r.get("father_name")),
            numberOrZero(r.get("gmat_score")),
            numberOrZero(r.get("sat_score")),
            numberOrZero(r.get("pp_exam_score")),
            orNA(r.get("pp_exam_cleared")),
            orNA(r.get("interview_status")),
            orNA(r.get("interview_result")),
            orNA(r.get("interview_remarks")),
            orNA(r.get("verification_status")),
            orNA(r.get("verification_remarks")),
            orNA(r.get("rejection_reasons")),
            emptyIfNull(r.get("contact_no1")),
            emptyIfNull(r.get("current_institute_dise_code")),
            emptyIfNull(r.get("medium")),
            emptyIfNull(r.get("school_name")),
            orNA(r.get("division_name")),
            orNA(r.get("district_name")),
            orNA(r.get("block_name"))
        );
    }

    /** Node `x || 'N/A'`. */
    private static Object orNA(Object v) { return (v == null || "".equals(v)) ? "N/A" : v; }

    /** Node `x` passthrough (no fallback) — blank cell if null. */
    private static Object emptyIfNull(Object v) { return v == null ? "" : v; }

    /** Node `Number(x || 0)`. */
    private static double numberOrZero(Object v) {
        if (v == null) return 0d;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return 0d; }
    }

    private static LocalDate asLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        return LocalDate.parse(String.valueOf(v)); // genericRow emits DATE as "yyyy-MM-dd"
    }

    /** Filename builder for downloadByBlocks — matches resultandrankingController.js:238-278 verbatim. */
    public String blocksFilename(Object division, Object district, List<Object> blocks, List<Map<String, Object>> results) {
        Set<String> uniqueDivisions = uniqueNonBlank(results, "division_name");
        Set<String> uniqueDistricts = uniqueNonBlank(results, "district_name");
        Set<String> uniqueBlocks = uniqueNonBlank(results, "block_name");

        StringBuilder fn = new StringBuilder("results");
        if (present(division)) {
            fn.append('_').append(!uniqueDivisions.isEmpty()
                ? sanitize(uniqueDivisions.iterator().next())
                : "Division_" + division);
        } else {
            fn.append("_All_Divisions");
        }
        if (present(district)) {
            fn.append('_').append(!uniqueDistricts.isEmpty()
                ? sanitize(uniqueDistricts.iterator().next())
                : "District_" + district);
        } else {
            fn.append("_All_Districts");
        }
        if (blocks != null && !blocks.isEmpty()) {
            if (uniqueBlocks.size() == 1) fn.append('_').append(sanitize(uniqueBlocks.iterator().next()));
            else if (uniqueBlocks.size() > 1) fn.append('_').append(uniqueBlocks.size()).append("_Blocks");
            else fn.append("_Selected_Blocks");
        } else {
            fn.append("_All_Blocks");
        }
        fn.append(".xlsx");
        return fn.toString().replaceAll("_+", "_");
    }

    /** Filename builder for downloadByExam — matches resultandrankingController.js:398-421 verbatim. */
    public String examFilename(List<Map<String, Object>> results) {
        StringBuilder fn = new StringBuilder("results");
        Object examName = results.isEmpty() ? null : results.get(0).get("exam_name");
        if (examName != null && !String.valueOf(examName).isBlank()) {
            String sanitized = sanitize(String.valueOf(examName));
            fn.append('_').append(sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized);
        } else {
            fn.append("_Exam");
        }
        Object examDate = results.isEmpty() ? null : results.get(0).get("exam_date");
        LocalDate d = asLocalDate(examDate);
        if (d != null) {
            fn.append('_').append(EXAM_FILENAME_DATE.format(d));
        }
        fn.append(".xlsx");
        return fn.toString().replaceAll("_+", "_");
    }

    private static boolean present(Object v) { return v != null && !String.valueOf(v).isBlank(); }

    private static Set<String> uniqueNonBlank(List<Map<String, Object>> rows, String key) {
        Set<String> set = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get(key);
            if (v != null && !String.valueOf(v).isBlank()) set.add(String.valueOf(v));
        }
        return set;
    }

    /** JS `/[^\w\s]/gi` strip then `/\s+/g` -> '_'. \w = [A-Za-z0-9_], \s = whitespace -- same classes in Java regex. */
    private static String sanitize(String s) {
        return s.replaceAll("[^\\w\\s]", "").replaceAll("\\s+", "_");
    }

    private byte[] build(String sheetName, List<String> headers, String fillArgb,
                         List<Map<String, Object>> rows, java.util.function.Function<Map<String, Object>, List<Object>> rowFn) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(
                    javaAwtColorFromArgb(fillArgb), null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(headerStyle);
            }

            int[] maxLen = new int[headers.size()];
            for (int c = 0; c < headers.size(); c++) maxLen[c] = headers.get(c).length();

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rowFn.apply(rows.get(r));
                for (int c = 0; c < headers.size(); c++) {
                    Object v = c < values.size() ? values.get(c) : null;
                    Cell cell = row.createCell(c);
                    int len;
                    if (v instanceof Double dv) { cell.setCellValue(dv); len = String.valueOf(dv).length(); }
                    else if (v == null || "".equals(v)) { cell.setCellValue(""); len = 10; } // ExcelJS empty-cell default length
                    else { String s = String.valueOf(v); cell.setCellValue(s); len = s.length(); }
                    if (len > maxLen[c]) maxLen[c] = len;
                }
            }
            for (int c = 0; c < headers.size(); c++) {
                int width = Math.min(Math.max(maxLen[c] + 2, 15), 50);
                sheet.setColumnWidth(c, width * 256); // POI width units = 1/256th of a character
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static java.awt.Color javaAwtColorFromArgb(String argb) {
        // argb e.g. "FFE6E6FA" -> skip alpha (FF), take RGB
        int r = Integer.parseInt(argb.substring(2, 4), 16);
        int g = Integer.parseInt(argb.substring(4, 6), 16);
        int b = Integer.parseInt(argb.substring(6, 8), 16);
        return new java.awt.Color(r, g, b);
    }
}
