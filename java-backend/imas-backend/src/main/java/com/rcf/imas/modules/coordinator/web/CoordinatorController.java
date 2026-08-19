package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository;
import com.rcf.imas.modules.coordinator.persistence.CoordinatorWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coordinator")
@PreAuthorize("isAuthenticated()")   // coordinatorRoutes.js: every route is `authenticate`-gated EXCEPT bare GET "/"
public class CoordinatorController {

    private final CoordinatorReadRepository reads;
    private final CoordinatorWriteRepository writes;

    public CoordinatorController(CoordinatorReadRepository reads, CoordinatorWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    /** coordinatorRoutes.js:489-491 -- the ONLY route with no `authenticate` middleware. */
    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    public String home() { return "Coordinator Home"; }

    @GetMapping("/institutes/search")
    public List<Map<String, Object>> instituteSearch(@RequestParam(required = false) String q) {
        if (q == null || q.trim().length() < 3) return List.of();
        try {
            return reads.instituteSearch(q.trim());
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to search institutes. Please try again.").with("success", false);
        }
    }

    @GetMapping("/teachers")
    public List<Map<String, Object>> teachers() {
        try {
            return reads.teachers();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch teachers");
        }
    }

    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        try {
            return reads.platforms();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch platforms");
        }
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects() {
        try {
            return reads.subjects();
        } catch (Exception e) {
            throw ApiException.error(500, "Internal server error");
        }
    }

    @GetMapping("/classrooms")
    public List<Map<String, Object>> allClassrooms() {
        try {
            return reads.allClassrooms();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch classrooms");
        }
    }

    @GetMapping("/classrooms/{batchId}")
    public List<Map<String, Object>> classroomsByBatch(@PathVariable String batchId) {
        try {
            return reads.classroomsByBatch(batchId);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch classrooms");
        }
    }

    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.cohortsByUser(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch cohorts");
        }
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(name = "cohort_number", required = false) String cohortNumber,
                                               @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            // Node guards on JS truthiness (`if (cohort_number)`), where "" is falsy -- a cleared filter
            // dropdown sends `?cohort_number=`. Treat blank as absent so we don't feed ''::integer to SQL.
            cohortNumber = blankToNull(cohortNumber);
            if (cohortNumber != null) {
                return reads.batchesByCohort(cohortNumber, principal.userId());
            }
            return reads.allBatchesForCoordinator(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch batches").with("details", e.getMessage());
        }
    }

    /** getStudentsController -- `classroomId` is accepted for wire compatibility but never used (dead in
     *  live Node too, see plan's "disagreements" section). */
    @GetMapping("/students")
    public List<Map<String, Object>> students(@RequestParam(required = false) String cohortNumber,
                                                @RequestParam(required = false) String batchId,
                                                @RequestParam(required = false) String classroomId,
                                                @RequestParam(required = false) String isAttendance,
                                                @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            // Node guards on JS truthiness (cohortNumber && batchId), where "" is falsy -- a cleared filter
            // sends `?cohortNumber=`/`?batchId=`. Treat blank as absent so empty strings fall through to the
            // unscoped list (Node's behavior) instead of hitting ''::integer -> SQLException -> 500.
            cohortNumber = blankToNull(cohortNumber);
            batchId = blankToNull(batchId);
            if ("true".equals(isAttendance) && cohortNumber != null && batchId != null) {
                return reads.activeStudentsForAttendance(cohortNumber, batchId);
            }
            if (cohortNumber != null && batchId != null) {
                return reads.studentsByCohortAndBatch(cohortNumber, batchId);
            }
            if (cohortNumber != null) {
                return reads.studentsByCoordinatorAndCohort(principal.userId(), cohortNumber);
            }
            return reads.studentsByCoordinator(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch students");
        }
    }

    @PostMapping("/classrooms")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createClassroom(@RequestBody Map<String, Object> body) {
        try {
            return writes.createClassroom(
                    (String) body.get("classroom_name"),
                    body.get("subject_id") == null ? null : String.valueOf(body.get("subject_id")),
                    body.get("teacher_id") == null ? null : String.valueOf(body.get("teacher_id")),
                    body.get("platform_id") == null ? null : String.valueOf(body.get("platform_id")),
                    (String) body.get("class_link"),
                    (String) body.get("active_yn"),
                    body.get("created_by") == null ? null : String.valueOf(body.get("created_by")),
                    body.get("updated_by") == null ? null : String.valueOf(body.get("updated_by")));
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to create classroom");
        }
    }

    /** updateStudentController parity. Inactive-branch condition matches Node's exact truthiness check:
     *  active_yn present, case-insensitively "INACTIVE", AND inactive_reason present and non-blank. */
    @PutMapping("/students/{id}")
    public Map<String, Object> updateStudent(@PathVariable String id, @RequestBody Map<String, Object> payload,
                                               @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            Object activeYn = payload.get("active_yn");
            Object inactiveReason = payload.get("inactive_reason");
            boolean inactiveBranch = activeYn != null && String.valueOf(activeYn).equalsIgnoreCase("INACTIVE")
                    && inactiveReason != null && !String.valueOf(inactiveReason).isBlank();

            if (inactiveBranch) {
                writes.markStudentInactive(id, String.valueOf(inactiveReason), principal.userId());
                return Map.of("message", "Student marked inactive successfully");
            }

            Map<String, Object> normalized = new java.util.HashMap<>(payload);
            if (activeYn != null) normalized.put("active_yn", String.valueOf(activeYn).toUpperCase());
            writes.updateStudent(id, normalized);
            return Map.of("message", "Student updated successfully");
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to update student");
        }
    }

    /** markInactiveController parity. LIVE-SOURCE CORRECTION vs. the ground truth doc: this 400 uses
     *  {error:...}, NOT {message:...} (server/controllers/coordinator/studentController.js:283) -- see
     *  plan's "disagreements" section. */
    @PutMapping("/students/{id}/inactive")
    public Map<String, Object> markInactive(@PathVariable String id, @RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal JwtService.FinalToken principal) {
        Object reason = body.get("inactive_reason");
        if (reason == null || String.valueOf(reason).isBlank()) {
            throw ApiException.error(400, "Inactive reason is required");
        }
        try {
            writes.markStudentInactive(id, String.valueOf(reason), principal.userId());
            return Map.of("message", "Student marked inactive successfully");
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to mark student inactive");
        }
    }

    @GetMapping("/students/{id}/inactive-history")
    public List<Map<String, Object>> inactiveHistory(@PathVariable String id) {
        try {
            return reads.inactiveHistory(id);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch inactive history");
        }
    }

    /** Mirrors Node's JS-truthiness param guards: a present-but-empty query param ("") is falsy in Node,
     *  so treat blank as absent rather than passing it on to an ::integer/::numeric cast. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
