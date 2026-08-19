package com.rcf.imas.modules.teacher.web;

import com.rcf.imas.modules.teacher.persistence.TeacherReadRepository;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/teacher")
@PreAuthorize("isAuthenticated()")   // teacherStudentRoutes.js: every one of the 9 routes is `auth`-gated,
                                       // no role check (ground truth §0) -- mirror CoordinatorController.
public class TeacherController {

    private final TeacherReadRepository reads;

    public TeacherController(TeacherReadRepository reads) {
        this.reads = reads;
    }

    /** #1 getCohortsController. */
    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.cohorts(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #2 getBatchesController -- Node's `if (cohort_number)` is JS-truthiness: "" is falsy, so a cleared
     *  filter dropdown (?cohort_number=) must fall through to the unfiltered branch, not ''::integer -> 500. */
    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(name = "cohort_number", required = false) String cohortNumber,
                                                @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            cohortNumber = blankToNull(cohortNumber);
            if (cohortNumber != null) {
                return reads.batchesByCohort(principal.userId(), cohortNumber);
            }
            return reads.batches(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #6 getTeacherProfileController -- photo_link is a hardcoded string template (ground truth §7.5),
     *  never a DB column; keyed by the AUTHENTICATED principal's userId, never a client param. */
    @GetMapping("/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal JwtService.FinalToken principal) {
        Map<String, Object> profile;
        try {
            profile = reads.profile(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
        if (profile == null) {
            throw ApiException.error(404, "Teacher profile not found");
        }
        // LinkedHashMap preserves genericRow's SELECT-column order (+ photo_link appended last), matching
        // Node's `{...row, photo_link}` spread; a plain HashMap would scramble the JSON key order.
        Map<String, Object> withPhoto = new LinkedHashMap<>(profile);
        withPhoto.put("photo_link", "user-photos/" + principal.userId() + ".jpg");
        return withPhoto;
    }

    /** #7 getTeacherCoordinatorsController -- photo_link injected per-row, same hardcoded convention. */
    @GetMapping("/coordinators")
    public List<Map<String, Object>> coordinators(@AuthenticationPrincipal JwtService.FinalToken principal) {
        List<Map<String, Object>> coordinators;
        try {
            coordinators = reads.coordinators(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
        return coordinators.stream().map(row -> {
            Map<String, Object> withPhoto = new LinkedHashMap<>(row);   // preserve SELECT key order (see /profile)
            withPhoto.put("photo_link", "user-photos/" + row.get("user_id") + ".jpg");
            return withPhoto;
        }).toList();
    }

    /** #3 getTimetableController -- Node "removed the strict requirement for batchId" (comment in
     *  TeacherTimetableController.js), so batchId is optional; blank ("") treated as absent. */
    @GetMapping("/timetable")
    public List<Map<String, Object>> timetable(@RequestParam(required = false) String batchId,
                                                  @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            batchId = blankToNull(batchId);
            if (batchId != null) {
                return reads.timetableByBatch(principal.userId(), batchId);
            }
            return reads.timetable(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #4 getStudentsController -- both cohortNumber AND batchId must be present to switch to the
     *  batch-scoped query; either missing falls through to the all-students-for-teacher branch. */
    @GetMapping("/students")
    public List<Map<String, Object>> students(@RequestParam(required = false) String cohortNumber,
                                                 @RequestParam(required = false) String batchId,
                                                 @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            cohortNumber = blankToNull(cohortNumber);
            batchId = blankToNull(batchId);
            if (cohortNumber != null && batchId != null) {
                return reads.studentsByTeacherAndBatch(principal.userId(), cohortNumber, batchId);
            }
            return reads.studentsByTeacher(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch students.");
        }
    }

    /** #5 getInactiveHistoryController -- studentId comes from the URL path, NOT the JWT principal (ground
     *  truth §7.1: no teacher-ownership check in Node, preserved verbatim -- see plan's Deferred section). */
    @GetMapping("/students/{id}/inactive-history")
    public List<Map<String, Object>> inactiveHistory(@PathVariable String id) {
        try {
            return reads.inactiveHistory(id);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch inactive history.");
        }
    }

    /** #8 getTeacherDashboardController. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            String userId = principal.userId();
            Map<String, Object> overview = reads.dashboardOverview(userId);
            List<Map<String, Object>> subjectAnalysis = reads.dashboardSubjectAnalysis(userId);
            List<Map<String, Object>> monthlyTrend = reads.dashboardMonthlyTrend(userId);
            // LinkedHashMap (not Map.of) to pin the response key order overview/subjectAnalysis/monthlyTrend,
            // matching Node's dashboard object assembly.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("overview", overview);
            out.put("subjectAnalysis", subjectAnalysis);
            out.put("monthlyTrend", monthlyTrend);
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #9 getMyClassReportsController -- requires both fromDate and toDate present (raw strings, no
     *  server-side range validation beyond presence, matching Node -- ground truth §2 #9). */
    @GetMapping("/reports/my-classes")
    public Map<String, Object> myClassReports(@RequestParam(required = false) String fromDate,
                                                 @RequestParam(required = false) String toDate,
                                                 @AuthenticationPrincipal JwtService.FinalToken principal) {
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            throw ApiException.error(400, "fromDate and toDate are required");
        }
        try {
            List<Map<String, Object>> classes = reads.myClassReports(principal.userId(), fromDate, toDate);
            return Map.of("classes", classes);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** Mirrors Node's JS-truthiness param guards: a present-but-empty query param ("") is falsy in Node,
     *  so treat blank as absent rather than passing it on to an ::integer/::numeric cast. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
