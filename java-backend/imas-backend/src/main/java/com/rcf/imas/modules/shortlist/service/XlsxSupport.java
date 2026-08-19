package com.rcf.imas.modules.shortlist.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Builds a single-sheet XLSX (header row + data rows) as a byte[]. */
@Component
public class XlsxSupport {

    public byte[] build(String sheetName, List<String> headers, List<Map<String, Object>> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) headerRow.createCell(c).setCellValue(headers.get(c));
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < headers.size(); c++) {
                    Object v = data.get(headers.get(c));
                    row.createCell(c).setCellValue(v == null ? "" : String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
