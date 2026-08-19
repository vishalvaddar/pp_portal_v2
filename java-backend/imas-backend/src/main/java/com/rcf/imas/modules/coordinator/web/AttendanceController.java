package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.AttendanceReadRepository;
import com.rcf.imas.modules.coordinator.persistence.AttendanceWriteRepository;
import com.rcf.imas.modules.coordinator.service.AttendanceSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coordinator/attendance")
@PreAuthorize("isAuthenticated()")   // Node: attendanceController routes all use `authenticate`
public class AttendanceController {

    private final AttendanceReadRepository reads;
    private final AttendanceWriteRepository writes;
    private final com.rcf.imas.modules.coordinator.service.AttendanceCsvPreviewService csvPreviewService;

    public AttendanceController(AttendanceReadRepository reads, AttendanceWriteRepository writes,
                                  com.rcf.imas.modules.coordinator.service.AttendanceCsvPreviewService csvPreviewService) {
        this.reads = reads;
        this.writes = writes;
        this.csvPreviewService = csvPreviewService;
    }

    /** getOrFindSession -- {session_id:null} 200 (NOT 404) when nothing matches, Node parity. */
    @GetMapping("/session")
    public Map<String, Object> getOrFindSession(@RequestParam(value = "classroom_id", required = false) String classroomId,
                                                  @RequestParam(value = "session_date", required = false) String sessionDate,
                                                  @RequestParam(value = "start_time", required = false) String startTime) {
        try {
            String normalized = AttendanceSupport.normalizeTimeToDB(startTime);
            return reads.getOrFindSession(classroomId, sessionDate, normalized)
                    .map(row -> row)
                    .orElseGet(() -> {
                        Map<String, Object> nullSession = new HashMap<>();
                        nullSession.put("session_id", null);
                        return nullSession;
                    });
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage());
        }
    }

    /** checkOverlap -- {overlap:boolean} ONLY, see plan Disagreements #1. */
    @GetMapping("/check-overlap")
    public Map<String, Object> checkOverlap(@RequestParam(value = "classroomId", required = false) String classroomId,
                                               @RequestParam(value = "date", required = false) String date,
                                               @RequestParam(value = "startTime", required = false) String startTime,
                                               @RequestParam(value = "endTime", required = false) String endTime) {
        boolean overlap = reads.checkOverlap(classroomId, date, startTime, endTime);
        return Map.of("overlap", overlap);
    }

    /** undoLastAttendanceCommit. */
    @PostMapping("/undo")
    public Map<String, Object> undo(@RequestBody Map<String, Object> body) {
        try {
            Object sid = body.get("session_id");
            // Node binds a missing session_id as NULL -> the DELETEs match nothing -> still {message} 200.
            // Skip the DB call for a blank/absent id rather than feeding 'null'::integer and 500ing.
            if (sid != null && !String.valueOf(sid).isBlank() && !"null".equalsIgnoreCase(String.valueOf(sid))) {
                writes.undoAttendance(String.valueOf(sid));
            }
            return Map.of("message", "Undo Successful");
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage());
        }
    }

    /** submitBulkAttendance -- STUB, does nothing (Firm Decision 5 / ground truth §8.4). */
    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestBody(required = false) Map<String, Object> ignoredBody) {
        return Map.of("message", "Bulk submission logic active");
    }

    /** downloadSampleCSV -- bundled classpath resource (Firm Decision 6, original Node asset absent). */
    @GetMapping("/csv/reference")
    public ResponseEntity<Resource> sampleCsv() {
        Resource resource = new ClassPathResource("attendance-assets/sample_attendance.csv");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample_attendance.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }

    /** previewCSVAttendance -- multipart CSV, in-memory fuzzy match, NO DB write. */
    @PostMapping(value = "/csv/preview", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> previewCsv(
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam("batch_id") String batchId) {
        return csvPreviewService.preview(file, batchId);
    }

    /** commitCSVAttendance -- @Transactional write, see Firm Decision 2. */
    @SuppressWarnings("unchecked")
    @PostMapping("/csv/commit")
    public Map<String, Object> commitCsv(@RequestBody Map<String, Object> body) {
        try {
            String classroomId = String.valueOf(body.get("classroom_id"));
            String sessionDate = String.valueOf(body.get("session_date"));
            String startTime = String.valueOf(body.get("start_time"));
            String endTime = String.valueOf(body.get("end_time"));
            List<Map<String, Object>> previewData = (List<Map<String, Object>>) body.get("previewData");

            Integer sessionId = writes.commitCsvAttendance(
                    classroomId, sessionDate,
                    AttendanceSupport.normalizeTimeToDB(startTime), AttendanceSupport.normalizeTimeToDB(endTime),
                    previewData == null ? java.util.List.of() : previewData);

            return Map.of("session_id", sessionId);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage());
        }
    }

    /** fetchAttendance -- manual-entry tab: students + their db_status for a session. */
    @GetMapping
    public java.util.List<Map<String, Object>> fetchAttendance(
            @RequestParam(value = "session_id", required = false) String sessionId,
            @RequestParam(value = "batchId", required = false) String batchId) {
        try {
            return reads.fetchAttendance(sessionId, batchId);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage());
        }
    }
}
