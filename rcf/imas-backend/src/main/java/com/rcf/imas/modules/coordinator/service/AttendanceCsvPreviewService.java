package com.rcf.imas.modules.coordinator.service;

import com.rcf.imas.modules.coordinator.persistence.AttendanceReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * previewCSVAttendance ported verbatim (live, attendanceController.js:474-614). In-memory only, NO DB write.
 * Positional (array-of-arrays) CSV parse -- Zoom export shape, row 0 = header, row 1 col D (index 3) = total
 * meeting duration, data rows from index 2, columns A=name(0), D=duration(3), E=time_joined(4), F=time_exited(5).
 * Java reads the MultipartFile stream directly (Firm Decision 4) instead of Node's write-to-disk-then-unlink
 * dance -- wire-invisible, and avoids Node's real leak on early-return/exception paths.
 */
@Service
public class AttendanceCsvPreviewService {

    private record CsvRow(String originalName, int durationMinutes, String timeJoined, String timeExited) {}

    private final AttendanceReadRepository reads;

    public AttendanceCsvPreviewService(AttendanceReadRepository reads) { this.reads = reads; }

    public Map<String, Object> preview(MultipartFile file, String batchId) {
        if (file == null || file.isEmpty()) {
            throw ApiException.message(400, "No file uploaded");
        }

        List<CSVRecord> records;
        try (InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            records = CSVParser.parse(reader, CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build())
                    .getRecords();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (records.size() < 2) {
            throw ApiException.message(400, "CSV missing data rows.");
        }

        String summaryDurationRaw = cell(records.get(1), 3);
        int totalCSVDurationMins = AttendanceSupport.parseDurationToMinutes(summaryDurationRaw);

        // csvMap: keyed by name.toLowerCase(), keeping the LARGEST-duration row per key. LinkedHashMap
        // preserves insertion order -- required for "first match wins" substring search below.
        Map<String, CsvRow> csvMap = new LinkedHashMap<>();
        for (int i = 2; i < records.size(); i++) {
            CSVRecord row = records.get(i);
            String rawName = cell(row, 0);
            if (rawName == null || rawName.isBlank()) continue;
            rawName = rawName.trim();
            String key = rawName.toLowerCase();
            int duration = AttendanceSupport.parseDurationToMinutes(cell(row, 3));

            CsvRow existing = csvMap.get(key);
            if (existing == null || existing.durationMinutes() < duration) {
                csvMap.put(key, new CsvRow(rawName, duration, cell(row, 4), cell(row, 5)));
            }
        }

        List<Map<String, Object>> previewData = new ArrayList<>();
        List<Map<String, Object>> unmatchedStudents = new ArrayList<>();
        List<Map<String, Object>> inactiveStudents = new ArrayList<>();
        Set<String> matchedCSVKeys = new LinkedHashSet<>();

        for (Map<String, Object> student : reads.attendanceStudentsByBatch(batchId)) {
            String studentName = String.valueOf(student.get("student_name"));
            String dbNameClean = studentName.trim().toLowerCase();

            String matchedKey = null;
            CsvRow matched = null;
            for (Map.Entry<String, CsvRow> e : csvMap.entrySet()) {
                if (e.getKey().contains(dbNameClean)) {
                    matchedKey = e.getKey();
                    matched = e.getValue();
                    break;
                }
            }

            boolean active = "ACTIVE".equals(student.get("active_yn"));
            if (!active) {
                if (matched != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("student_name", studentName);
                    row.put("duration_minutes", matched.durationMinutes());
                    inactiveStudents.add(row);
                    matchedCSVKeys.add(matchedKey);
                }
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("student_id", student.get("student_id"));
            row.put("student_name", studentName);
            row.put("enr_id", student.get("enr_id"));
            if (matched != null) {
                double pct = totalCSVDurationMins > 0
                        ? (matched.durationMinutes() / (double) totalCSVDurationMins) * 100 : 0;
                row.put("duration_minutes", matched.durationMinutes());
                row.put("time_joined", matched.timeJoined());
                row.put("time_exited", matched.timeExited());
                row.put("status", pct >= 75 ? "PRESENT" : (pct >= 40 ? "LATE JOINED" : "ABSENT"));
                matchedCSVKeys.add(matchedKey);
            } else {
                row.put("duration_minutes", 0);
                row.put("time_joined", "N/A");
                row.put("time_exited", "N/A");
                row.put("status", "ABSENT");
            }
            previewData.add(row);
        }

        for (Map.Entry<String, CsvRow> e : csvMap.entrySet()) {
            if (!matchedCSVKeys.contains(e.getKey())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("student_name", e.getValue().originalName());
                row.put("duration_minutes", e.getValue().durationMinutes());
                unmatchedStudents.add(row);
            }
        }

        // Node sorts with String.prototype.localeCompare (locale collation), NOT UTF-16 code-unit order --
        // use a Collator so mixed-case/punctuation names order the same on the wire.
        java.text.Collator collator = java.text.Collator.getInstance();
        previewData.sort((a, b) -> collator.compare(String.valueOf(a.get("student_name")),
                String.valueOf(b.get("student_name"))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("previewData", previewData);
        response.put("unmatchedStudents", unmatchedStudents);
        response.put("inactiveStudents", inactiveStudents);
        return response;
    }

    private static String cell(CSVRecord row, int index) {
        return index < row.size() ? row.get(index) : null;
    }
}
