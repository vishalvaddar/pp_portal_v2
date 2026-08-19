package com.rcf.imas.modules.coordinator.service;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorReportsRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the JS-side response reshaping from reportsController.js (nested Maps built in the controller
 * body, not SQL) -- kept in a service so it's unit-testable independent of MockMvc/DB and so the controller
 * methods stay thin.
 */
@Service
public class CoordinatorReportsService {

    private final CoordinatorReportsRepository reports;

    public CoordinatorReportsService(CoordinatorReportsRepository reports) {
        this.reports = reports;
    }

    /** getAttendanceReport -- {reportId, cohort_name, batch_name, subjects, students}. */
    public Map<String, Object> attendanceReport(String batchId, String fromDate, String toDate) {
        List<Map<String, Object>> info = reports.attendanceBatchInfo(batchId);
        String batchName = info.isEmpty() || info.get(0).get("batch_name") == null ? "" : String.valueOf(info.get(0).get("batch_name"));
        String cohortName = info.isEmpty() || info.get(0).get("cohort_name") == null ? "" : String.valueOf(info.get(0).get("cohort_name"));

        Map<String, Object> conductedStructured = new LinkedHashMap<>();
        for (Map<String, Object> r : reports.attendanceConducted(batchId, fromDate, toDate)) {
            String subjectCode = String.valueOf(r.get("subject_code"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> bucket = (List<Map<String, Object>>) conductedStructured
                    .computeIfAbsent(subjectCode, k -> new java.util.ArrayList<Map<String, Object>>());
            // LEFT JOIN teacher -> teacher_name can be null; Map.of NPEs on null, so use a null-tolerant map
            // (Node pushes {teacher_name: null, conducted} fine -> serializes teacher_name:null).
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("teacher_name", r.get("teacher_name"));
            entry.put("conducted", parseIntOrZero(r.get("conducted")));
            bucket.add(entry);
        }

        Map<String, Map<String, Object>> studentMap = new LinkedHashMap<>();
        for (Map<String, Object> r : reports.attendanceByStudent(batchId, fromDate, toDate)) {
            String studentId = String.valueOf(r.get("student_id"));
            Map<String, Object> student = studentMap.computeIfAbsent(studentId, k -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", studentId);
                s.put("name", r.get("student_name"));
                s.put("subjects", new LinkedHashMap<String, Object>());
                return s;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> subjects = (Map<String, Object>) student.get("subjects");
            String subjectCode = String.valueOf(r.get("subject_code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> teacherAttended = (Map<String, Object>) subjects
                    .computeIfAbsent(subjectCode, k -> new LinkedHashMap<String, Object>());
            teacherAttended.put(String.valueOf(r.get("teacher_name")), parseIntOrZero(r.get("attended")));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", "ATT-" + batchId + "-" + fromDate + "-" + toDate);
        response.put("cohort_name", cohortName);
        response.put("batch_name", batchName);
        response.put("subjects", conductedStructured);
        response.put("students", List.copyOf(studentMap.values()));
        return response;
    }

    /** Node: `parseInt(r.conducted, 10)` / `parseInt(r.attended || 0, 10)`. genericRow's BIGINT branch
     *  already turns COUNT(...) into a String -- watch a real "0" String, do NOT let it fall through to a
     *  null-coalesce fallback (ground truth §5 numeric-id note). */
    private static int parseIntOrZero(Object value) {
        if (value == null) return 0;
        return Integer.parseInt(String.valueOf(value));
    }

    /** getAbsenteesReport -- {reportId, students:[{id,name,missedClasses,totalMissed}]}. */
    public Map<String, Object> absenteesReport(String batchId, String fromDate, String toDate) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> r : reports.absentees(batchId, fromDate, toDate)) {
            String studentId = String.valueOf(r.get("student_id"));
            Map<String, Object> student = grouped.computeIfAbsent(studentId, k -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", studentId);
                s.put("name", r.get("student_name"));
                s.put("missedClasses", new java.util.ArrayList<Map<String, Object>>());
                s.put("totalMissed", 0);
                return s;
            });
            int missedCount = parseIntOrZero(r.get("missed_count"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> missedClasses = (List<Map<String, Object>>) student.get("missedClasses");
            missedClasses.add(Map.of(
                    "subject", r.get("subject"),
                    "count", missedCount,
                    "dates", unwrapDateArray(r.get("missed_dates"))));
            student.put("totalMissed", (Integer) student.get("totalMissed") + missedCount);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", "ABS-" + batchId + "-" + fromDate + "-" + toDate);
        response.put("students", List.copyOf(grouped.values()));
        return response;
    }

    /** missed_dates is a Postgres date[] -- genericRow hands back a raw java.sql.Array for this column.
     *  Node's `.filter(Boolean)` drops the NULL placeholder entries the CASE/ARRAY_AGG can produce; do the
     *  same here. */
    private static List<String> unwrapDateArray(Object arrayObj) {
        if (arrayObj == null) return List.of();
        try {
            java.sql.Array sqlArray = (java.sql.Array) arrayObj;
            Object[] raw = (Object[]) sqlArray.getArray();
            List<String> out = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o == null) continue;
                out.add(o instanceof java.sql.Date d ? d.toLocalDate().toString() : String.valueOf(o));
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** getTeacherPerformance -- {reportId, subjects:[{subject,scheduled,conducted,completion}]}. Subject
     *  key set = union of scheduled ∪ conducted subject codes (Node builds subjectsMap from `scheduled`
     *  first, then folds `conducted` in, creating a fresh {scheduled:0,...} entry for conducted-only subjects). */
    public Map<String, Object> teacherPerformanceReport(String teacherId, String fromDate, String toDate) {
        Map<String, int[]> subjectsMap = new LinkedHashMap<>(); // [0]=scheduled, [1]=conducted
        for (Map<String, Object> r : reports.teacherPerformanceScheduled(teacherId, fromDate, toDate)) {
            subjectsMap.put(String.valueOf(r.get("subject")), new int[]{parseIntOrZero(r.get("scheduled")), 0});
        }
        for (Map<String, Object> r : reports.teacherPerformanceConducted(teacherId, fromDate, toDate)) {
            String subject = String.valueOf(r.get("subject"));
            int conducted = parseIntOrZero(r.get("conducted"));
            subjectsMap.computeIfAbsent(subject, k -> new int[]{0, 0})[1] = conducted;
        }

        List<Map<String, Object>> subjects = new java.util.ArrayList<>();
        for (Map.Entry<String, int[]> e : subjectsMap.entrySet()) {
            int scheduled = e.getValue()[0];
            int conducted = e.getValue()[1];
            double completion = scheduled > 0
                    ? Math.round((conducted / (double) scheduled) * 100 * 10) / 10.0
                    : 0.0;
            // Node: `+(x).toFixed(1)` yields a JS Number -> an integral result serializes as 100/50/0
            // (no trailing .0). Emit a whole number as a long so Jackson matches; keep fractional as double.
            Object completionVal = completion == Math.rint(completion) ? (Object) (long) completion : (Object) completion;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", e.getKey());
            row.put("scheduled", scheduled);
            row.put("conducted", conducted);
            row.put("completion", completionVal);
            subjects.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", "TP-" + teacherId + "-" + fromDate + "-" + toDate);
        response.put("subjects", subjects);
        return response;
    }
}
