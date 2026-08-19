package com.rcf.imas.modules.student.web;

import com.rcf.imas.modules.student.persistence.StudentPortalReadRepository;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/student")
@PreAuthorize("isAuthenticated()")   // studentRoutes.js: every route except "/" is `authenticate`-gated (student mobile app token)
class StudentPortalController {

    private final StudentPortalReadRepository reads;

    StudentPortalController(StudentPortalReadRepository reads) { this.reads = reads; }

    /** studentRoutes.js:25-27 -- the ONLY route in this file with no `authenticate` middleware. */
    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    public String health() { return "Student API Working"; }

    @GetMapping("/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.profileByUserId(principal.userId())
                    .orElseThrow(() -> ApiException.message(404, "Student profile not found"));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Server error");
        }
    }

    @GetMapping("/timetable")
    public List<Map<String, Object>> timetable(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            Map<String, Object> profile = reads.profileByUserId(principal.userId())
                    .orElseThrow(() -> ApiException.message(404, "Student profile not found."));
            Object batchId = profile.get("batch_id");
            if (batchId == null) {
                throw ApiException.message(400, "No batch assigned.");
            }
            return reads.timetableByBatchId(batchId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal Server Error");
        }
    }

    @GetMapping("/{id}/inactive-history")
    public List<Map<String, Object>> inactiveHistory(@PathVariable String id) {
        try {
            return reads.inactiveHistory(id);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch inactive history");
        }
    }

    /** #4 (/performance) and #6 (/subjects) are the SAME Node handler function -- one Java method, two paths. */
    @GetMapping({"/performance", "/subjects"})
    public List<Map<String, Object>> subjectPerformance(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.subjectPerformance(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch subject performance");
        }
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.summary(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch summary");
        }
    }

    @GetMapping("/monthly")
    public List<Map<String, Object>> monthly(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.monthlyAttendance(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch monthly data");
        }
    }

    @GetMapping("/weekly")
    public List<Map<String, Object>> weekly(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.weeklyAttendance(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch weekly data");
        }
    }

    @GetMapping("/custom")
    public List<Map<String, Object>> custom(@AuthenticationPrincipal JwtService.FinalToken principal,
                                             @RequestParam(required = false) String fromDate,
                                             @RequestParam(required = false) String toDate) {
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            throw ApiException.error(400, "Date range required");
        }
        try {
            return reads.customAttendance(principal.userId(), fromDate, toDate);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch custom data");
        }
    }
}
