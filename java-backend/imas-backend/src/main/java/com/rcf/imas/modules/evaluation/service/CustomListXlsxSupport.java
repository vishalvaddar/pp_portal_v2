package com.rcf.imas.modules.evaluation.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** POI port of customListController.js downloadListXLS: dynamic ID/Name-then-fields columns, shared special-case mapping. */
@Component
public class CustomListXlsxSupport {

    public byte[] build(List<Map<String, Object>> students, List<Map<String, Object>> fields) {
        boolean hasId = fields.stream().anyMatch(f -> "student_id".equals(f.get("col_name")));
        boolean hasName = fields.stream().anyMatch(f -> "student_name".equals(f.get("col_name")));

        List<String> headers = new ArrayList<>();
        List<String> colNames = new ArrayList<>();
        if (hasId) { headers.add("Student ID"); colNames.add("student_id"); }
        if (hasName) { headers.add("Student Name"); colNames.add("student_name"); }
        for (Map<String, Object> f : fields) {
            String col = String.valueOf(f.get("col_name"));
            if ("student_id".equals(col) || "student_name".equals(col)) continue;
            headers.add(String.valueOf(f.get("display_name")));
            colNames.add(col);
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Student List");

            CellStyle boldStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            boldStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(boldStyle);
            }

            for (int r = 0; r < students.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> s = students.get(r);
                for (int c = 0; c < colNames.size(); c++) {
                    row.createCell(c).setCellValue(CustomListValueMapper.cellText(colNames.get(c), s));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
