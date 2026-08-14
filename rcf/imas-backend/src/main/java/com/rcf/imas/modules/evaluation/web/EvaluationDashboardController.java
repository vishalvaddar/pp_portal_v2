package com.rcf.imas.modules.evaluation.web;

import com.rcf.imas.modules.evaluation.persistence.DashboardReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Each handler needs its OWN 500 message (Node has a distinct catch block per handler; unlike the rest of this
 * module, these do NOT share the generic {error:"Internal Server Error"} fallback) -- see Locked Conventions #5.
 */
@RestController
@RequestMapping("/api/evaluation-dashboard")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left these routes open
class EvaluationDashboardController {

    private final DashboardReadRepository dashboard;

    EvaluationDashboardController(DashboardReadRepository dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/overall/{year}")
    public Map<String, Object> overall(@PathVariable String year) {
        try {
            return dashboard.overallCounts(year);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch overall counts.");
        }
    }

    @GetMapping("/jurisdictions/{year}")
    public List<Map<String, Object>> jurisdictions(@PathVariable String year) {
        try {
            return dashboard.jurisdictionStatus(year);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch jurisdictional progress.");
        }
    }

    @GetMapping("/overall-progress/{year}")
    public Map<String, Object> overallProgress(@PathVariable String year) {
        try {
            return Map.of("overallProgress", dashboard.overallProgress(year));
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch overall progress.");
        }
    }
}
