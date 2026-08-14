package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorDashboardRepository;
import com.rcf.imas.modules.coordinator.service.CoordinatorDashboardService;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Dashboard endpoints #35-37 (ground truth §1). Firm Decision 1: real @PreAuthorize("isAuthenticated()")
 * -- Node's live /reports/global-attendance and /reports/teacher-subject-stats use the non-verifying
 * requireAuth (any Authorization header, never jwt.verify'd); #35 uses the real `authenticate` already.
 * This is a deliberate hardening for the two /reports/* routes, matching CoordinatorReportsController's
 * (4e-3) precedent. Kept as its own controller/file rather than folded into CoordinatorReportsController,
 * matching the ground truth's task-decomposition split (4e-3 = reports, 4e-4 = timetable+dashboards).
 */
@RestController
@RequestMapping("/api/coordinator")
@PreAuthorize("isAuthenticated()")
public class CoordinatorDashboardController {

    private final CoordinatorDashboardService dashboardService;
    private final CoordinatorDashboardRepository dashboardRepo;

    public CoordinatorDashboardController(CoordinatorDashboardService dashboardService, CoordinatorDashboardRepository dashboardRepo) {
        this.dashboardService = dashboardService;
        this.dashboardRepo = dashboardRepo;
    }

    @GetMapping("/attendance/batch-weekly-avg")
    public List<Map<String, Object>> batchWeeklyAverage(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return dashboardService.batchWeeklyAverage(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to load weekly attendance");
        }
    }

    @GetMapping("/reports/teacher-subject-stats")
    public List<Map<String, Object>> teacherSubjectStats(@RequestParam(required = false) String batchId) {
        try {
            return dashboardRepo.teacherSubjectStats(batchId);
        } catch (Exception e) {
            // Node: `res.status(500).json({error: err.message})` -- dynamic message, not a static string.
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/reports/global-attendance")
    public List<Map<String, Object>> globalAttendance() {
        try {
            return dashboardRepo.globalAttendanceStats();
        } catch (Exception e) {
            // Node: `res.status(500).json({error: err.message})` -- dynamic message, not a static string.
            throw ApiException.error(500, e.getMessage());
        }
    }
}
