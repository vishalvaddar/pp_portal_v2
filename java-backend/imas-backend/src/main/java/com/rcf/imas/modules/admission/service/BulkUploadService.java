package com.rcf.imas.modules.admission.service;

import com.rcf.imas.modules.admission.persistence.BulkInsertRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Service
public class BulkUploadService {

    private static final List<String> REQUIRED = List.of(
            "nmms_year", "nmms_reg_number", "student_name", "father_name", "gmat_score", "sat_score");
    private static final Set<String> NUMERIC = Set.of("nmms_year", "gmat_score", "sat_score");

    private final BulkInsertRepository bulkInsert;
    private final ApplicantFormatter formatter;

    public BulkUploadService(BulkInsertRepository bulkInsert, ApplicantFormatter formatter) {
        this.bulkInsert = bulkInsert;
        this.formatter = formatter;
    }

    /** Result mirrors the Node response object exactly. httpStatus carries the intended status code. */
    public record Result(int totalRecords, int insertedRecords, int validationErrors,
                         int dbErrors, String status, String logFile, int httpStatus) {}

    public Result process(MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.toLowerCase().endsWith(".csv") ? ".csv"
                : original.toLowerCase().endsWith(".xls") ? ".xls"
                : original.toLowerCase().endsWith(".xlsx") ? ".xlsx" : "";

        List<String> validationMessages = new ArrayList<>();
        List<String> dbErrors = new ArrayList<>();
        try {
            List<Map<String, String>> rows = ext.equals(".csv") ? parseCsv(file) : parseExcel(file);

            // validate + sanitize
            List<Map<String, Object>> valid = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> raw = rows.get(i);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("originalRowIndex", i);
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (REQUIRED.contains(k) && (v == null || v.trim().isEmpty())) {
                        validationMessages.add("Row " + (i + 1) + ": " + k + " This field is required.");
                    }
                    if ("dob".equals(k)) out.put(k, formatter.sanitizeBulkDate(v));
                    else if (NUMERIC.contains(k)) out.put(k, numeric(v));
                    else out.put(k, sanitize(v));
                }
                valid.add(out);
            }

            if (!validationMessages.isEmpty()) {
                String log = writeLog(original, "failed", validationMessages, dbErrors);
                return new Result(rows.size(), 0, validationMessages.size(), 0, "failed", log, 400);
            }

            int inserted;
            try {
                inserted = bulkInsert.insertBatch(valid, dbErrors);
            } catch (RuntimeException batchErr) {
                // batch rolled back (@Transactional in BulkInsertRepository) — nothing persisted.
                // Parity: Node's `inserted` array is discarded because the COMMIT never happened.
                inserted = 0;
            }
            boolean ok = dbErrors.isEmpty() && inserted > 0;
            String status = ok ? "success" : "failed";
            String log = writeLog(original, status, validationMessages, dbErrors);
            return new Result(rows.size(), inserted, 0, dbErrors.size(), status, log, ok ? 200 : 500);

        } catch (Exception ex) {
            dbErrors.add("CRITICAL ERROR: " + ex.getMessage());
            String log = writeLog(original, "failed", validationMessages, dbErrors);
            // critical catch: Node returns {message, status, logFile} with 500 — signalled by totalRecords<0
            return new Result(-1, 0, 0, 0, "failed", log, 500);
        }
    }

    // ---------- parsing ----------
    private List<Map<String, String>> parseCsv(MultipartFile file) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setTrim(true).build();
        try (Reader r = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(r, fmt)) {
            List<String> headers = parser.getHeaderNames().stream().map(BulkUploadService::normHeader).toList();
            for (CSVRecord rec : parser) {
                if (isBlankRecord(rec)) continue;   // skipEmptyLines parity
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < rec.size() ? rec.get(i) : "");
                }
                out.add(row);
            }
        }
        return out;
    }

    private List<Map<String, String>> parseExcel(MultipartFile file) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> it = sheet.iterator();
            if (!it.hasNext()) return out;
            Row headerRow = it.next();
            List<String> headers = new ArrayList<>();
            for (Cell c : headerRow) headers.add(normHeader(cellString(c)));
            DataFormatter df = new DataFormatter();
            while (it.hasNext()) {
                Row row = it.next();
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell c = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    m.put(headers.get(i), c == null ? "" : df.formatCellValue(c));  // defval:"" parity
                }
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isBlankRecord(CSVRecord rec) {
        for (String v : rec) if (v != null && !v.isBlank()) return false;
        return true;
    }

    private static String cellString(Cell c) {
        if (c == null) return "";
        return new DataFormatter().formatCellValue(c);
    }

    private static String normHeader(String h) {
        return h == null ? "" : h.toLowerCase().trim().replace(" ", "_");
    }

    // ---------- sanitize (bulk model parity) ----------
    private static Object numeric(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return Double.valueOf(v.trim()).longValue(); }  // isNaN → null
        catch (NumberFormatException e) { return null; }
    }

    private static String sanitize(String v) {
        if (v == null || v.isEmpty()) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    // ---------- log file ----------
    private static String writeLog(String fileName, String status,
                                   List<String> validationErrors, List<String> dbErrors) {
        String name = "upload_log_" + Instant.now().toEpochMilli() + ".txt";
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "imas-bulk-logs");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            sb.append("File Upload Summary\n============================\n");
            sb.append("File: ").append(fileName).append("\nStatus: ").append(status).append("\n\n");
            if (!validationErrors.isEmpty()) {
                sb.append("Validation Errors:\n");
                validationErrors.forEach(e -> sb.append(e).append("\n"));
            }
            if (!dbErrors.isEmpty()) {
                sb.append("\nDatabase Errors:\n");
                dbErrors.forEach(e -> sb.append("• ").append(e).append("\n"));
            }
            Files.writeString(dir.resolve(name), sb.toString());
        } catch (Exception ignored) {
            // log write failure must not break the response
        }
        return name;
    }
}
