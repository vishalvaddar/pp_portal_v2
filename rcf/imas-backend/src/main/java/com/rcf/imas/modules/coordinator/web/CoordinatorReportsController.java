package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorReportsRepository;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Firm Decision 1: real, JWT-verified @PreAuthorize("isAuthenticated()") on all 7 routes -- Node's live
 * reportsController.js requireAuth (line 5-9) only checks an Authorization header is PRESENT, never calls
 * jwt.verify. This is a deliberate hardening, closing that gap (ground truth doc §8.5).
 */
@RestController
@RequestMapping("/api/coordinator/reports")
@PreAuthorize("isAuthenticated()")
public class CoordinatorReportsController {

    private final CoordinatorReportsRepository reports;
    private final com.rcf.imas.modules.coordinator.service.CoordinatorReportsService service;

    public CoordinatorReportsController(CoordinatorReportsRepository reports,
                                          com.rcf.imas.modules.coordinator.service.CoordinatorReportsService service) {
        this.reports = reports;
        this.service = service;
    }

    /** getTeacherLoad -- {teacherClassCounts:[...]}, {message} error envelope (matches attendance module,
     *  NOT the {error} convention used elsewhere in coordinator -- reportsController.js:273-280). */
    @GetMapping("/teacher-load")
    public Map<String, Object> teacherLoad(@RequestParam(required = false) String fromDate,
                                             @RequestParam(required = false) String toDate) {
        try {
            return Map.of("teacherClassCounts", reports.teacherLoad(fromDate, toDate));
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error");
        }
    }

    /** getCoordinatorTeachers -- bare array, scoped by JWT principal (Firm Decision 8). */
    @GetMapping("/coordinator-teachers")
    public List<Map<String, Object>> coordinatorTeachers(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reports.coordinatorTeachers(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    /** getAttendanceReport -- {reportId, cohort_name, batch_name, subjects, students}. */
    @GetMapping("/attendance")
    public Map<String, Object> attendanceReport(@RequestParam(required = false) String batchId,
                                                   @RequestParam(required = false) String fromDate,
                                                   @RequestParam(required = false) String toDate) {
        try {
            return service.attendanceReport(batchId, fromDate, toDate);
        } catch (Exception e) {
            throw ApiException.error(500, "Server error generating attendance report");
        }
    }

    /** getAbsenteesReport -- {reportId, students:[{id,name,missedClasses,totalMissed}]}. */
    @GetMapping("/absentees")
    public Map<String, Object> absenteesReport(@RequestParam(name = "batch_id", required = false) String batchId,
                                                  @RequestParam(required = false) String fromDate,
                                                  @RequestParam(required = false) String toDate) {
        if (batchId == null || fromDate == null || toDate == null) {
            throw ApiException.error(400, "batch_id, fromDate, and toDate required");
        }
        try {
            return service.absenteesReport(batchId, fromDate, toDate);
        } catch (Exception e) {
            throw ApiException.error(500, "Server error generating absentees report");
        }
    }

    /** getTeacherPerformance -- {reportId, subjects:[{subject,scheduled,conducted,completion}]}. */
    @GetMapping("/teacher-performance")
    public Map<String, Object> teacherPerformance(@RequestParam(required = false) String teacherId,
                                                      @RequestParam(required = false) String fromDate,
                                                      @RequestParam(required = false) String toDate) {
        if (teacherId == null || fromDate == null || toDate == null) {
            throw ApiException.error(400, "teacherId, fromDate, and toDate required");
        }
        try {
            return service.teacherPerformanceReport(teacherId, fromDate, toDate);
        } catch (Exception e) {
            throw ApiException.error(500, "Server error generating teacher performance");
        }
    }

    /** getBatchClassDetails -- {success, count, classes} bare passthrough. */
    @GetMapping("/batch-class-details")
    public Map<String, Object> batchClassDetails(@RequestParam(required = false) String batchId,
                                                     @RequestParam(required = false) String fromDate,
                                                     @RequestParam(required = false) String toDate) {
        try {
            List<Map<String, Object>> classes = reports.batchClassDetails(batchId, fromDate, toDate);
            return Map.of("success", true, "count", classes.size(), "classes", classes);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** getTeacherClassDetails -- Firm Decision 4: closed 2-way switch lives in the repository; the
     *  request value is always a bound param, never concatenated into SQL. */
    @GetMapping("/teacher-class-details")
    public Map<String, Object> teacherClassDetails(@RequestParam(required = false) String teacherId,
                                                       @RequestParam(required = false) String fromDate,
                                                       @RequestParam(required = false) String toDate) {
        try {
            List<Map<String, Object>> classes = reports.teacherClassDetails(teacherId, fromDate, toDate);
            return Map.of("success", true, "count", classes.size(), "classes", classes);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }
}
