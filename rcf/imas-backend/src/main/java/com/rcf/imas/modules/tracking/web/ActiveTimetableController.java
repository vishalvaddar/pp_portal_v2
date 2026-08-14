package com.rcf.imas.modules.tracking.web;

import com.rcf.imas.modules.tracking.persistence.ActiveTimetableReadRepository;
import com.rcf.imas.modules.tracking.persistence.ActiveTimetableWriteRepository;
import com.rcf.imas.modules.tracking.service.TimetablePdfSupport;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/activetimetable")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node applies zero auth middleware to this mount (Firm Decision 1)
class ActiveTimetableController {

    private final ActiveTimetableReadRepository reads;
    private final ActiveTimetableWriteRepository writes;
    private final TimetablePdfSupport pdf;

    ActiveTimetableController(ActiveTimetableReadRepository reads, ActiveTimetableWriteRepository writes,
                               TimetablePdfSupport pdf) {
        this.reads = reads;
        this.writes = writes;
        this.pdf = pdf;
    }

    @GetMapping("/dropdowns")
    public Map<String, Object> dropdowns() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cohorts", reads.openCohorts());
            body.put("teachers", reads.allTeachers());
            return body;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam("cohortName") String cohortName) {
        try {
            return reads.batchesByCohortName(cohortName);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    /**
     * getTimetableData parity. type=combined/teacher/batch dispatch; unknown type -> 200 empty body
     * (Firm Decision 4e -- reproduces res.json(undefined)'s observed wire behavior, not null/[]/an error).
     */
    @GetMapping("/fetch")
    public ResponseEntity<List<Map<String, Object>>> fetch(@RequestParam(value = "type", required = false) String type,
                                                             @RequestParam(value = "id", required = false) String id,
                                                             @RequestParam(value = "cohort", required = false) String cohort) {
        try {
            // a plain `switch (type)` throws NPE on a null selector regardless of the default branch --
            // guard explicitly so a missing `type` reaches the same 200-empty quirk-4e path as an unknown one.
            List<Map<String, Object>> data = type == null ? null : switch (type) {
                case "combined" -> reads.combinedByCohort(id); // Node getCombined(id) reads `id`, not `cohort` (client sends both equal)
                case "teacher" -> reads.teacherWise(id); // quirk 4c: cohort intentionally ignored here
                case "batch" -> reads.batchWise(id, cohort);
                default -> null; // quirk 4e: unknown type -> 200 empty body, not an error
            };
            if (data == null) return ResponseEntity.ok().build();
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @PostMapping("/subject/add")
    public ResponseEntity<Map<String, Object>> addSubject(@RequestBody Map<String, Object> body,
                                                            @AuthenticationPrincipal JwtService.FinalToken principal) {
        Object codeRaw = body.get("subject_code");
        Object nameRaw = body.get("subject_name");
        // Guard BEFORE insert: String.valueOf(null) is the literal "null", which fits varchar(5) NOT NULL and
        // would be silently persisted as a bogus row. Node binds undefined as SQL NULL -> the NOT NULL
        // constraint rejects it (500, no row). Reject blank/absent here with a clean 400 instead.
        if (codeRaw == null || String.valueOf(codeRaw).isBlank()
                || nameRaw == null || String.valueOf(nameRaw).isBlank()) {
            throw ApiException.error(400, "Subject code and subject name are required");
        }
        String subjectCode = String.valueOf(codeRaw);
        String subjectName = String.valueOf(nameRaw);
        try {
            // Firm Decision 2: created_by comes from the authenticated principal, NOT req.body.admin_id
            // (Node's req.user ? req.user.user_id : req.body.admin_id always fell to the client-controlled
            // body field because no middleware ever set req.user -- a real integrity gap, fixed here).
            Map<String, Object> row = writes.addSubject(subjectCode, subjectName, principal.userId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Subject added successfully");
            out.put("data", row);
            return ResponseEntity.status(HttpStatus.CREATED).body(out);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.error(400, "Subject name already exists");
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to add subject to database");
        }
    }

    @GetMapping("/teacher-skills/{teacherId}")
    public Map<String, Object> teacherSkills(@PathVariable String teacherId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("skills", reads.teacherSkills(teacherId));
            out.put("allSubjects", reads.allSubjects());
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @PostMapping("/teacher-skills/manage")
    public Map<String, Object> manageTeacherSkill(@RequestBody Map<String, Object> body) {
        String teacherId = String.valueOf(body.get("teacherId"));
        String subjectId = String.valueOf(body.get("subjectId"));
        String medium = String.valueOf(body.get("medium"));
        String action = String.valueOf(body.get("action"));
        try {
            // Node: `if (action === 'add') add; else delete;` -- default (any non-'add' value) is DELETE.
            if ("add".equals(action)) {
                writes.addTeacherSkill(teacherId, subjectId, medium); // uppercased inside addTeacherSkill
            } else {
                writes.deleteTeacherSkill(teacherId, subjectId, medium); // NOT uppercased (quirk 4d)
            }
            return Map.of("message", "Skill updated successfully");
        } catch (Exception e) {
            // No 23505 special-case (unlike addSubject) -- raw driver message surfaces, matching Node.
            throw ApiException.error(500, "Database error: " + e.getMessage());
        }
    }

    @PostMapping("/download-pdf")
    public ResponseEntity<byte[]> downloadPdf(@RequestBody Map<String, Object> body) {
        try {
            byte[] bytes = pdf.build(body);
            String cohortName = String.valueOf(body.getOrDefault("cohortName", "timetable"));
            String fileName = body.get("fileName") != null
                    ? String.valueOf(body.get("fileName"))
                    : "TIMETABLE_" + cohortName + ".pdf";
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=" + fileName)
                    .body(bytes);
        } catch (Exception e) {
            throw ApiException.error(500, "Error generating PDF");
        }
    }
}
