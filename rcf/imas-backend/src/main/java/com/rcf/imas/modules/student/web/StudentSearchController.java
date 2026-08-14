package com.rcf.imas.modules.student.web;

import com.rcf.imas.modules.student.persistence.StudentSearchReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")   // studentSearchRoutes.js: zero `authenticate` middleware in Node -- NEW hardening
class StudentSearchController {

    private final StudentSearchReadRepository reads;

    StudentSearchController(StudentSearchReadRepository reads) { this.reads = reads; }

    @GetMapping("/search-students")
    public Map<String, Object> searchStudents(
            @RequestParam(required = false) String batch_id,
            @RequestParam(required = false) String cohort_number,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String enr_id,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String state_id,
            @RequestParam(required = false) String district_id,
            @RequestParam(required = false) String block_id,
            @RequestParam(required = false) String spl_health_cond,
            @RequestParam(required = false) String spl_family_cond,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        try {
            StudentSearchReadRepository.SearchResult result = reads.search(batch_id, cohort_number, name, enr_id,
                    gender, state_id, district_id, block_id, spl_health_cond, spl_family_cond, limit, offset);

            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("total", result.total());
            pagination.put("limit", result.limit());
            pagination.put("offset", result.offset());
            pagination.put("page", result.offset() / result.limit() + 1);
            pagination.put("totalPages", (long) Math.ceil((double) result.total() / result.limit()));
            pagination.put("hasMore", result.offset() + result.limit() < result.total());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", result.rows());
            body.put("pagination", pagination);
            return body;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("success", false);
        }
    }

    /**
     * 500 body is LITERALLY {success:false} with NEITHER "error" NOR "message" key (studentSearchController.js:42
     * `res.status(500).json({ success: false })`). ApiException always carries exactly one of those two keys, so
     * this one exceptional case bypasses it and returns a ResponseEntity directly.
     */
    @GetMapping("/student/{studentId}")
    public Object byId(@PathVariable String studentId) {
        Map<String, Object> row;
        try {
            row = reads.byId(studentId).orElse(null);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false));
        }
        if (row == null) {
            throw ApiException.message(404, "Student not found").with("success", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", row);
        return body;
    }
}
