package com.rcf.imas.modules.classroom.web;

import com.rcf.imas.modules.classroom.persistence.BatchReadRepository;
import com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository;
import com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository.BatchWriteResult;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batches")
@PreAuthorize("hasRole('ADMIN')")   // ground truth: zero Node `authenticate` middleware on this mount -- Firm Decision 1
class BatchController {

    /** Duplicated in BatchReadRepository too (convention #13). */
    static final int COHORT_START_YEAR = 2021;

    private final BatchReadRepository reads;
    private final com.rcf.imas.modules.classroom.persistence.BatchWriteRepository writes;
    private final ClassroomWriteRepository classroomWrites;

    BatchController(BatchReadRepository reads, com.rcf.imas.modules.classroom.persistence.BatchWriteRepository writes,
                     ClassroomWriteRepository classroomWrites) {
        this.reads = reads;
        this.writes = writes;
        this.classroomWrites = classroomWrites;
    }

    @PostMapping({"", "/"})
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createBatch(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = str(b.get("batch_name"));
        String cohortNumber = str(b.get("cohort_number"));
        String coordinatorId = str(b.get("coordinator_id"));
        if (isBlank(name) || isBlank(cohortNumber)) {
            throw ApiException.error(400, "batch_name and cohort_number are required");
        }
        try {
            var result = classroomWrites.createBatch(name.trim(), cohortNumber, coordinatorId);   // Node trims batch_name (batchController.js:36,41)
            if (result.status() == BatchWriteResult.Status.CONFLICT) {
                throw ApiException.error(409, "Batch already exists for this cohort.");
            }
            return result.row();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @PutMapping("/{batchId}")
    public Map<String, Object> updateBatch(@PathVariable String batchId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = str(b.get("batch_name"));
        String cohortNumber = str(b.get("cohort_number"));
        String coordinatorId = str(b.get("coordinator_id"));
        // batch_status is read from the body into NOTHING -- Firm Decision 3, ground truth §7 quirk 2.
        // No column exists to persist it to; it is simply never looked at again below.
        if (isBlank(name) || isBlank(cohortNumber)) {
            throw ApiException.error(400, "Missing required fields");
        }
        try {
            var result = classroomWrites.updateBatch(batchId, name.trim(), cohortNumber, coordinatorId);   // Node trims batch_name (batchController.js:98,103)
            if (result.status() == BatchWriteResult.Status.CONFLICT) throw ApiException.error(409, "Duplicate batch name in cohort.");
            if (result.status() == BatchWriteResult.Status.NOT_FOUND) throw ApiException.error(404, "Batch not found");
            return result.row();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @DeleteMapping("/{batchId}")
    public Map<String, Object> deleteBatch(@PathVariable String batchId) {
        // Deliberately no broad try/catch here (Firm Decision 6) -- an FK-violation exception from
        // classroomWrites.deleteBatch() must propagate to GlobalExceptionHandler's generic 500, not be
        // rewrapped by an ApiException in this handler.
        var result = classroomWrites.deleteBatch(batchId);
        if (result.status() == BatchWriteResult.Status.NOT_FOUND) {
            throw ApiException.error(404, "Batch not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Batch deleted successfully");
        out.put("deleted", result.row());
        return out;
    }

    @PostMapping("/names")
    public org.springframework.http.ResponseEntity<Map<String, Object>> addBatchName(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String batchName = str(b.get("batch_name"));
        String cohortNumber = str(b.get("cohort_number"));
        String createdBy = str(b.get("created_by"));
        // Node has THREE separate checks in this order (batchController.js:163-168), each with its own message.
        if (isBlank(batchName)) throw ApiException.error(400, "Batch name is required");
        if (isBlank(cohortNumber)) throw ApiException.error(400, "Cohort number is required");
        if (isBlank(createdBy)) throw ApiException.error(400, "Created by (user ID) is required");
        try {
            Map<String, Object> row = writes.insertBatchName(batchName.trim(), cohortNumber, createdBy);   // Node trims batch_name (batchController.js:171)
            if (row == null) {
                return org.springframework.http.ResponseEntity.ok(Map.of("message", "Batch name already exists for this cohort"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Batch created successfully");
            out.put("batch", row);
            return org.springframework.http.ResponseEntity.status(201).body(out);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @PostMapping("/cohorts")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createCohort(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String cohortName = str(b.get("cohort_name"));
        String startDate = str(b.get("start_date"));
        String description = str(b.get("description"));
        if (isBlank(cohortName) || isBlank(startDate)) {
            throw ApiException.error(400, "cohort_name and start_date are required");
        }
        String trimmedName = cohortName.trim();   // Node trims cohort_name for both the dup-check and the insert (batchController.js:209,223)
        String cleanDesc = isBlank(description) ? null : description;   // Node `description || null` -> "" becomes NULL
        try {
            // Node order (batchController.js:209-221): name-exists 409 -> date-parse 400 -> year-exists 409.
            if (writes.cohortNameExists(trimmedName)) throw ApiException.error(409, "Cohort name already exists");
            int year;
            try {
                // Node `new Date(start_date)` is lenient (accepts ISO datetimes); take the leading yyyy-MM-dd.
                year = java.time.LocalDate.parse(startDate.length() >= 10 ? startDate.substring(0, 10) : startDate).getYear();
            } catch (Exception e) {
                throw ApiException.error(400, "Invalid start_date format");   // Node batchController.js:215
            }
            int cohortNumber = year - COHORT_START_YEAR;
            if (writes.cohortYearExists(cohortNumber)) throw ApiException.error(409, "Cohort for year " + year + " already exists.");
            Map<String, Object> row = writes.insertCohort(cohortNumber, trimmedName, startDate, cleanDesc);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Cohort created successfully");
            out.put("data", row);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/coordinators")
    public List<Map<String, Object>> coordinators() {
        try {
            return reads.coordinators();
        } catch (BatchReadRepository.CoordinatorRoleNotFoundException e) {
            throw ApiException.error(404, "Coordinator role not found");
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/names")
    public List<Map<String, Object>> names() {
        try {
            return reads.batchNames().stream().map(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", n);
                m.put("value", n);
                return m;
            }).toList();
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts() {
        try { return reads.allCohorts(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @GetMapping("/cohorts/active")
    public List<Map<String, Object>> activeCohorts() {
        try { return reads.activeCohorts(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @GetMapping("/students/unassigned")
    public List<Map<String, Object>> studentsUnassigned() {
        try { return reads.studentsNotInAnyBatch(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    /** endpoint #18: distinct 404 envelope KEY ("message") from Task 5's updateStudentStatusInBatch, which
     *  reuses this same repository lookup but reports 404 under "error" -- convention #7, do not unify. */
    @GetMapping("/students/{enr_id}")
    public Map<String, Object> studentInfo(@PathVariable("enr_id") String enrId) {
        try {
            Map<String, Object> row = reads.studentInfoByEnrId(enrId)
                    .orElseThrow(() -> ApiException.message(404, "Student not found"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reg_number", row.get("nmms_reg_number"));
            out.putAll(row);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/{cohort_number}/batches")
    public List<Map<String, Object>> batchesByCohort(@PathVariable("cohort_number") String cohortNumber) {
        try { return reads.batchesByCohortBatchSide(cohortNumber); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @GetMapping({"", "/"})
    public List<Map<String, Object>> allBatches() {
        try { return reads.allBatches(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    /** endpoint #22: "Batch not found." WITH trailing period -- distinct from #24 deleteBatch's "Batch not
     *  found" WITHOUT one (convention #7). */
    @GetMapping("/{batchId}")
    public Map<String, Object> batchById(@PathVariable String batchId) {
        try {
            return reads.batchById(batchId).orElseThrow(() -> ApiException.error(404, "Batch not found."));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/{batchId}/students")
    public List<Map<String, Object>> studentsInBatch(@PathVariable String batchId) {
        try { return reads.studentsInBatch(batchId); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @PostMapping("/{batchId}/add-students")
    public Map<String, Object> addStudentsToBatch(@PathVariable String batchId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        List<String> ids = asStringList(b.get("student_ids"));
        // Node has two separate checks (batchController.js:346,350). batchId is a path var (always present),
        // so the reachable case is an empty student_ids list -> "student_ids array is required".
        if (isBlank(batchId)) throw ApiException.error(400, "batchId is required");
        if (ids.isEmpty()) throw ApiException.error(400, "student_ids array is required");
        try {
            int count = writes.addStudentsToBatch(batchId, ids);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Students successfully assigned to batch");
            out.put("count", count);
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @PostMapping("/students/remove")
    public Map<String, Object> removeStudents(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        // batch_id, if present, is read and silently ignored (ground truth §7 quirk 10) -- removal is NOT
        // scoped by batch, matching Node's removeStudentBatchId/removeStudentsFromBatch exactly.
        List<String> ids = asStringList(b.get("student_ids"));
        if (ids.isEmpty()) throw ApiException.error(400, "student_ids are required");
        try {
            int count = writes.removeStudentsFromBatch(ids);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Students removed from batch successfully");
            out.put("count", count);
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** NOTE: batchId is accepted per the route shape but NEVER used to scope the update (ground truth §7
     *  quirk 9) -- preserved verbatim; a student belonging to a different batch than :batchId in the URL
     *  still updates successfully. Reuses reads.studentInfoByEnrId (the same repository method backing
     *  endpoint #18) to resolve enr_id -> student_id, matching Node's own reuse of fetchStudentInfoByEnrId
     *  here (the "dead student_id param" branch, ground truth §7 quirk 9, is never reachable -- Spring's
     *  @PathVariable model has no such param at all, so there is nothing to even omit). */
    @PutMapping("/{batchId}/students/{enr_id}/status")
    public Map<String, Object> updateStudentStatusInBatch(@PathVariable String batchId,
                                                            @PathVariable("enr_id") String enrId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String activeYn = str(b.get("active_yn"));
        if (isBlank(enrId)) throw ApiException.error(400, "student_id or enr_id is required");
        // Node: `if (active_yn == null)` -- rejects only null/undefined, ACCEPTS "" (batchController.js:404-405).
        if (activeYn == null) throw ApiException.error(400, "active_yn is required");
        try {
            Map<String, Object> info = reads.studentInfoByEnrId(enrId)
                    .orElseThrow(() -> ApiException.error(404, "Student not found")); // "error" key -- distinct from endpoint #18
            Object studentId = info.get("student_id");
            Map<String, Object> updated = writes.updateStudentStatus(activeYn, studentId);
            if (updated == null) throw ApiException.error(404, "Student not found");
            return Map.of("message", "Student status updated successfully");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // The ONE handler in this module that echoes err.message under "details" (ground truth §7 quirk 11).
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static boolean isBlank(String s) { return s == null || s.isBlank(); }
    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }
}
